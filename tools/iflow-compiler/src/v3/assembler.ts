/** Two-pass assembler and ROM v3 emitter. */

import { createHash } from 'crypto';
import type {
  DirectiveStatement,
  Expr,
  InstructionStatement,
  SourceFile,
  Statement,
} from './ast';
import {
  classifyInstruction,
  encodeInstruction,
  instructionLength,
  type InstructionPlan,
} from './encoder';
import { parseSource } from './parser';
import {
  AssemblyError,
  InstructionForm,
  SECTION_TYPE_NAMES,
  SEGMENT_FLAGS,
  SectionType,
  isKnownCapability,
  type AssembleOptions,
  type Diagnostic,
  type RomResult,
  type SegmentResult,
  type SymbolInfo,
} from './types';

const HEADER_SIZE = 80;
const TOC_OFFSET = 80;
const TOC_ENTRY_SIZE = 64;
const MAX_ROM_SIZE = 16 * 1024 * 1024;
const MAX_TOC_ENTRIES = 256;
const MAX_PROGRAMS = 64;
const PAGE_SIZE = 4096;
const PRG3_SIZE = 32;
const ADDRESS_SPACE_END = 0x1_0000_0000;
const ROM_END = 0x0100_0000;
const USER_BASE = 0x0200_0000;
const USER_END = 0x8000_0000;
const KERNEL_BASE = 0x8000_0000;

const DEFAULT_ORG: Readonly<Record<SectionType, number>> = {
  [SectionType.BIOS]: 0x0000_0000,
  [SectionType.BOOT]: 0x8000_0000,
  [SectionType.KERNEL]: 0x8000_1000,
  [SectionType.PROGRAM]: 0x0200_0000,
  [SectionType.DATA]: 0x8000_2000,
  [SectionType.FONT]: 0x8000_3000,
  [SectionType.CONFIG]: 0x8000_4000,
};

const DEFAULT_FLAGS: Readonly<Record<SectionType, number>> = {
  [SectionType.BIOS]: SEGMENT_FLAGS.R | SEGMENT_FLAGS.X,
  [SectionType.BOOT]: SEGMENT_FLAGS.R | SEGMENT_FLAGS.X,
  [SectionType.KERNEL]: SEGMENT_FLAGS.R | SEGMENT_FLAGS.X,
  [SectionType.PROGRAM]: SEGMENT_FLAGS.R | SEGMENT_FLAGS.X | SEGMENT_FLAGS.U,
  [SectionType.DATA]: SEGMENT_FLAGS.R | SEGMENT_FLAGS.W,
  [SectionType.FONT]: SEGMENT_FLAGS.R,
  [SectionType.CONFIG]: SEGMENT_FLAGS.R,
};

interface SectionBuilder {
  readonly name: string;
  readonly type: SectionType;
  org: number;
  flags: number;
  entryName?: string;
  capabilities: string[];
  statements: Statement[];
  location: number;
  size: number;
}

interface SegmentLayout {
  readonly section: SectionBuilder;
  readonly bytes: Uint8Array;
  readonly memSize: number;
  readonly entryPoint: number;
}

export class AssemblerV3 {
  private readonly diagnostics: Diagnostic[] = [];
  private readonly labels = new Map<string, SymbolInfo>();
  private readonly sections: SectionBuilder[] = [];
  private resetName?: string;
  private resetVector = 0;
  private defaultBiosOrg = PAGE_SIZE;
  private sourceStatements: readonly Statement[] = [];
  private sourceFile = 'flowasm';

  assemble(source: string, file: string, options: AssembleOptions = {}): RomResult {
    this.diagnostics.length = 0;
    this.labels.clear();
    this.sections.length = 0;
    this.resetName = undefined;
    this.resetVector = 0;
    this.defaultBiosOrg = PAGE_SIZE;
    const parsed = parseSource(source, file, this.diagnostics);
    this.sourceFile = file;
    this.sourceStatements = parsed.statements;
    const sectionCount = parsed.statements.filter(statement => statement.kind === 'directive'
      && statement.name === 'section').length;
    this.defaultBiosOrg = align(HEADER_SIZE + sectionCount * TOC_ENTRY_SIZE, PAGE_SIZE);
    this.layout(parsed);
    this.validateSections();
    this.resetVector = this.resolveReset(options);
    const segments = this.buildSegments();
    this.validateEntries(segments);
    const bytes = this.writeRom(segments, options.maxRomSize ?? MAX_ROM_SIZE);

    if (this.hasErrors()) {
      throw new AssemblyError(this.diagnostics);
    }

    return {
      bytes,
      resetVector: this.resetVector,
      segments: segments.map(segment => ({
        name: segment.section.name,
        type: segment.section.type,
        flags: segment.section.flags,
        loadAddress: segment.section.org,
        entryPoint: segment.entryPoint,
        bytes: segment.bytes,
        memSize: segment.memSize,
        symbols: this.symbolsOf(segment.section.name),
      })),
      symbols: [...this.labels.values()],
      diagnostics: this.diagnostics,
    };
  }

  private layout(source: SourceFile): void {
    let current: SectionBuilder | undefined;
    for (const statement of source.statements) {
      if (statement.kind === 'label') {
        if (!current) {
          this.error(statement, 'label outside a section');
          continue;
        }
        this.defineLabel(statement.name, current, statement.line, statement.column);
        continue;
      }
      if (statement.kind === 'directive') {
        if (statement.name === 'section') {
          current = this.openSection(statement);
          continue;
        }
        if (statement.name === 'reset') {
          this.resetName = statement.textArguments[0];
          if (!this.resetName) {
            this.error(statement, '.reset requires a label');
          }
          continue;
        }
        if (!current) {
          this.error(statement, `directive .${statement.name} outside a section`);
          continue;
        }
        this.layoutDirective(statement, current);
        continue;
      }
      if (!current) {
        this.error(statement, `instruction outside a section: ${statement.mnemonic}`);
        continue;
      }
      current.statements.push(statement);
      const plan = classifyInstruction(statement, (message, line, column) =>
        this.errorAt(message, line, column));
      if (!plan) {
        this.error(statement, `cannot encode instruction '${statement.mnemonic}'`);
        continue;
      }
      current.location += instructionLength(plan);
    }

    for (const section of this.sections) {
      section.size = section.location - this.startOf(section);
      if (section.entryName !== undefined && !this.labels.has(section.entryName)) {
        this.errorAt(`undefined entry label '${section.entryName}'`, 1, 1);
      }
    }
  }

  private openSection(statement: DirectiveStatement): SectionBuilder | undefined {
    const rawName = statement.textArguments[0];
    if (!rawName) {
      this.error(statement, '.section requires a name');
      return undefined;
    }
    const typeKey = rawName.split('.')[0].toLowerCase();
    const type = SECTION_TYPE_NAMES[typeKey];
    if (type === undefined) {
      this.error(statement, `unknown section type '${rawName}'`);
      return undefined;
    }
    if (this.sections.some(section => section.name === rawName)) {
      this.error(statement, `duplicate section '${rawName}'`);
      return undefined;
    }
    const org = type === SectionType.BIOS ? this.defaultBiosOrg : DEFAULT_ORG[type];
    const section: SectionBuilder = {
      name: rawName,
      type,
      org,
      flags: DEFAULT_FLAGS[type],
      capabilities: [],
      statements: [],
      location: type === SectionType.PROGRAM ? org + PRG3_SIZE : org,
      size: 0,
    };
    this.sections.push(section);
    return section;
  }

  private layoutDirective(statement: DirectiveStatement, section: SectionBuilder): void {
    switch (statement.name) {
      case 'org': {
        if (section.statements.length > 0 || section.location !== this.startOf(section)) {
          this.error(statement, '.org must appear before section content');
          break;
        }
        const value = this.constValue(statement, statement.arguments[0]);
        if (value !== undefined) {
          if (!Number.isInteger(value) || value < 0 || value > 0xFFFF_FFFF) {
            this.error(statement, '.org must be a 32-bit unsigned address');
            break;
          }
          section.org = value >>> 0;
          section.location = section.org + (section.type === SectionType.PROGRAM ? PRG3_SIZE : 0);
        }
        break;
      }
      case 'align': {
        const value = this.constValue(statement, statement.arguments[0]);
        if (value !== undefined) {
          if (value <= 0 || value > MAX_ROM_SIZE || (value & (value - 1)) !== 0) {
            this.error(statement,
              `.align requires a power of two no larger than ${MAX_ROM_SIZE}`);
            break;
          }
          section.location = align(section.location, value);
        }
        break;
      }
      case 'space': {
        const value = this.constValue(statement, statement.arguments[0]);
        if (value !== undefined) {
          if (value < 0) {
            this.error(statement, '.space cannot be negative');
          } else if (value > MAX_ROM_SIZE) {
            this.error(statement, `.space exceeds the ${MAX_ROM_SIZE}-byte ROM limit`);
          } else {
            section.location += value;
          }
        }
        break;
      }
      case 'byte':
        section.location += statement.arguments.length;
        break;
      case 'half':
        section.location += statement.arguments.length * 2;
        break;
      case 'word':
        section.location += statement.arguments.length * 4;
        break;
      case 'string':
        for (const text of statement.textArguments) {
          section.location += Buffer.byteLength(text, 'utf8') + 1;
        }
        break;
      case 'entry':
        section.entryName = statement.textArguments[0];
        if (!section.entryName) {
          this.error(statement, '.entry requires a label');
        }
        break;
      case 'capability':
        if (section.type !== SectionType.PROGRAM) {
          this.error(statement, '.capability is only valid in a program section');
        }
        section.capabilities.push(...statement.textArguments);
        break;
      case 'flags':
        section.flags = this.parseFlags(statement);
        break;
      case 'globl':
        break;
      case 'section':
      case 'reset':
        break;
    }
  }

  private emitDirective(
    statement: DirectiveStatement,
    section: SectionBuilder,
    buffer: Uint8Array,
    offset: number,
  ): number {
    switch (statement.name) {
      case 'byte': {
        let written = 0;
        for (const expr of statement.arguments) {
          const value = this.value(expr, statement);
          if (value < -128 || value > 255) {
            this.error(statement, `.byte value out of range: ${value}`);
          }
          buffer[offset + written++] = value & 0xff;
        }
        return written;
      }
      case 'half': {
        let written = 0;
        for (const expr of statement.arguments) {
          const value = this.value(expr, statement);
          if (value < -32768 || value > 65535) {
            this.error(statement, `.half value out of range: ${value}`);
          }
          writeU16(buffer, offset + written, value);
          written += 2;
        }
        return written;
      }
      case 'word': {
        let written = 0;
        for (const expr of statement.arguments) {
          const value = this.value(expr, statement);
          if (value < -0x8000_0000 || value > 0xFFFF_FFFF) {
            this.error(statement, `.word value out of range: ${value}`);
          }
          writeU32(buffer, offset + written, value);
          written += 4;
        }
        return written;
      }
      case 'string': {
        let written = 0;
        for (const text of statement.textArguments) {
          const encoded = Buffer.from(text, 'utf8');
          buffer.set(encoded, offset + written);
          written += encoded.length;
          buffer[offset + written++] = 0;
        }
        return written;
      }
      case 'space': {
        const value = this.constValue(statement, statement.arguments[0]);
        return value === undefined ? 0 : Math.max(0, value);
      }
      case 'align': {
        const value = this.constValue(statement, statement.arguments[0]);
        if (value === undefined || value <= 0) {
          return 0;
        }
        const address = section.org
          + (section.type === SectionType.PROGRAM ? PRG3_SIZE : 0)
          + offset;
        return align(address, value) - address;
      }
      case 'section':
      case 'org':
      case 'entry':
      case 'reset':
      case 'capability':
      case 'flags':
      case 'globl':
        return 0;
    }
  }

  private buildSegments(): SegmentLayout[] {
    return this.sections.map(section => {
      const code = this.emitSectionBytes(section);
      const isProgram = section.type === SectionType.PROGRAM;
      const content = isProgram ? this.wrapProgram(section, code) : code;
      const entry = section.entryName ? this.labels.get(section.entryName)?.address ?? 0
        : this.startOf(section);
      return {
        section,
        bytes: content,
        memSize: content.length,
        entryPoint: isExecutable(section.flags) ? entry : 0,
      };
    });
  }

  private emitSectionBytes(section: SectionBuilder): Uint8Array {
    const result = new Uint8Array(Math.max(0, section.size));
    let current: SectionBuilder | undefined;
    let offset = 0;
    for (const statement of this.allStatements()) {
      if (statement.kind === 'directive' && statement.name === 'section') {
        current = this.sections.find(value => value.name === statement.textArguments[0]);
        offset = 0;
        continue;
      }
      if (current !== section) {
        continue;
      }
      if (statement.kind === 'label') {
        continue;
      }
      if (statement.kind === 'directive') {
        const written = this.emitDirective(statement, section, result, offset);
        offset += written;
        continue;
      }
      const plan = classifyInstruction(statement, (message, line, column) =>
        this.errorAt(message, line, column));
      if (!plan) {
        continue;
      }
      const base = section.org + (section.type === SectionType.PROGRAM ? PRG3_SIZE : 0);
      const encoded = encodeInstruction(plan, base + offset, expr => {
        const value = this.value(expr, statement);
        const immediate = plan.form === InstructionForm.IMMEDIATE && !plan.relative;
        if (immediate && (!Number.isInteger(value)
            || value < -0x8000_0000 || value > 0xFFFF_FFFF)) {
          this.error(statement, `immediate out of range: ${value}`);
          return 0;
        }
        return value;
      });
      result.set(encoded, offset);
      offset += encoded.length;
    }
    return result;
  }

  private allStatements(): readonly Statement[] {
    return this.sourceStatements;
  }

  private wrapProgram(section: SectionBuilder, code: Uint8Array): Uint8Array {
    if (section.capabilities.length > 256) {
      this.errorAt(
        `section '${section.name}' has more than 256 capabilities`, 1, 1);
    }
    const capabilities: Uint8Array[] = [];
    let capabilitiesLength = 2;
    for (const capability of section.capabilities) {
      const encoded = Buffer.from(capability, 'utf8');
      if (encoded.length > 128) {
        this.errorAt(`capability exceeds 128 bytes: ${capability}`, 1, 1);
      }
      if (encoded.length === 0 || encoded.includes(0)) {
        this.errorAt('capability name must be non-empty and contain no NUL', 1, 1);
      }
      if (!isKnownCapability(capability)) {
        this.errorAt(`unknown capability: ${capability}`, 1, 1);
      }
      const item = new Uint8Array(2 + encoded.length);
      writeU16(item, 0, encoded.length);
      item.set(encoded, 2);
      capabilities.push(item);
      capabilitiesLength += item.length;
    }
    const content = new Uint8Array(PRG3_SIZE + code.length + capabilitiesLength);
    content.set(Buffer.from('PRG3', 'ascii'), 0);
    writeU32(content, 4, PRG3_SIZE);
    writeU32(content, 8, PRG3_SIZE + code.length);
    writeU32(content, 12, 0);
    writeU32(content, 16, 0);
    writeU32(content, 20, PRG3_SIZE + code.length);
    writeU32(content, 24, capabilitiesLength);
    writeU32(content, 28, 0);
    content.set(code, PRG3_SIZE);
    let offset = PRG3_SIZE + code.length;
    writeU16(content, offset, capabilities.length);
    offset += 2;
    for (const capability of capabilities) {
      content.set(capability, offset);
      offset += capability.length;
    }
    return content;
  }

  private writeRom(segments: readonly SegmentLayout[], maxRomSize: number): Uint8Array {
    const tocCount = segments.length;
    const firstFree = align(HEADER_SIZE + tocCount * TOC_ENTRY_SIZE, 16);
    const biosIndex = segments.findIndex(
      segment => segment.section.type === SectionType.BIOS);
    const offsets = new Array<number>(segments.length).fill(0);
    let offset = firstFree;

    // The BIOS is mapped directly from the container, so its ROM offset must be
    // its execution address. Every other segment is placed after it.
    if (biosIndex >= 0) {
      const bios = segments[biosIndex];
      offsets[biosIndex] = bios.section.org;
      offset = Math.max(offset, bios.section.org + bios.bytes.length);
    }
    segments.forEach((segment, index) => {
      if (index === biosIndex) {
        return;
      }
      offset = align(offset, 16);
      offsets[index] = offset;
      offset += segment.bytes.length;
    });
    const romSize = offset;
    if (romSize > maxRomSize) {
      this.errorAt(`ROM is ${romSize} bytes, above the ${maxRomSize}-byte limit`, 1, 1);
    }

    const rom = new Uint8Array(romSize);
    rom.set(Buffer.from('FLOWROM\0', 'ascii'), 0);
    writeU16(rom, 8, 3);
    writeU16(rom, 10, 32);
    writeU16(rom, 12, 3);
    writeU16(rom, 14, HEADER_SIZE);
    writeU32(rom, 16, 0);
    writeU32(rom, 20, romSize);
    writeU32(rom, 24, this.resetVector);
    writeU32(rom, 28, TOC_OFFSET);
    writeU16(rom, 32, tocCount);
    writeU16(rom, 34, TOC_ENTRY_SIZE);
    writeU32(rom, 36, 1);
    writeU32(rom, 40, 0);
    writeU32(rom, 44, 0);

    segments.forEach((segment, index) => {
      const toc = TOC_OFFSET + index * TOC_ENTRY_SIZE;
      const content = segment.bytes;
      rom[toc] = segment.section.type;
      rom[toc + 1] = segment.section.flags;
      writeU32(rom, toc + 4, offsets[index]);
      writeU32(rom, toc + 8, content.length);
      writeU32(rom, toc + 12, segment.section.org);
      writeU32(rom, toc + 16, segment.entryPoint);
      writeU32(rom, toc + 20, segment.memSize);
      const hash = createHash('sha256').update(content).digest();
      if (hash.every(value => value === 0)) {
        this.errorAt(`segment '${segment.section.name}' has an empty hash`, 1, 1);
      }
      rom.set(hash, toc + 24);
      rom.set(content, offsets[index]);
    });

    const globalHash = createHash('sha256').update(rom).digest();
    rom.set(globalHash, 48);
    return rom;
  }

  private validateSections(): void {
    const counts = new Map<SectionType, number>();
    for (const section of this.sections) {
      counts.set(section.type, (counts.get(section.type) ?? 0) + 1);
      if (section.size <= 0) {
        this.errorAt(`section '${section.name}' is empty`, 1, 1);
      }
      if ((section.org & (PAGE_SIZE - 1)) !== 0) {
        this.errorAt(`section '${section.name}' load address must be 4 KiB aligned`, 1, 1);
      }
      if ((section.flags & SEGMENT_FLAGS.W) !== 0 && (section.flags & SEGMENT_FLAGS.X) !== 0) {
        this.errorAt(`section '${section.name}' cannot be writable and executable`, 1, 1);
      }
      if ((section.flags & ~0x0f) !== 0) {
        this.errorAt(`section '${section.name}' has reserved flags`, 1, 1);
      }
      if (section.type === SectionType.BIOS && section.org < this.defaultBiosOrg) {
        this.errorAt(
          `BIOS must load at or above 0x${this.defaultBiosOrg.toString(16).padStart(8, '0')}`,
          1,
          1);
      }
      if (section.type === SectionType.PROGRAM
          && (section.flags & SEGMENT_FLAGS.U) === 0) {
        this.errorAt('PROGRAM requires U=1', 1, 1);
      }
      if (section.type === SectionType.PROGRAM
          && (section.flags & SEGMENT_FLAGS.W) !== 0) {
        this.errorAt('PROGRAM TOC cannot be writable', 1, 1);
      }
      if (section.type !== SectionType.PROGRAM
          && (section.flags & SEGMENT_FLAGS.U) !== 0) {
        this.errorAt('only PROGRAM can be user-accessible', 1, 1);
      }
      this.validateSectionRules(section);
    }
    for (const required of [SectionType.BIOS, SectionType.BOOT, SectionType.KERNEL]) {
      if ((counts.get(required) ?? 0) !== 1) {
        this.errorAt(`ROM requires exactly one ${SectionType[required]} segment`, 1, 1);
      }
    }
    if ((counts.get(SectionType.PROGRAM) ?? 0) > MAX_PROGRAMS) {
      this.errorAt(`at most ${MAX_PROGRAMS} PROGRAM segments are allowed`, 1, 1);
    }
    if (this.sections.length > MAX_TOC_ENTRIES) {
      this.errorAt(`at most ${MAX_TOC_ENTRIES} TOC entries are allowed`, 1, 1);
    }
    for (let i = 0; i < this.sections.length; i += 1) {
      for (let j = i + 1; j < this.sections.length; j += 1) {
        const a = this.sections[i];
        const b = this.sections[j];
        const aSize = Math.max(a.size + (a.type === SectionType.PROGRAM ? PRG3_SIZE : 0), 1);
        const bSize = Math.max(b.size + (b.type === SectionType.PROGRAM ? PRG3_SIZE : 0), 1);
        if (overlaps(a.org, aSize, b.org, bSize)) {
          this.errorAt(
            `sections '${a.name}' and '${b.name}' overlap in virtual memory`, 1, 1);
        }
      }
    }
  }

  private validateSectionRules(section: SectionBuilder): void {
    const start = section.org;
    const size = section.size + (section.type === SectionType.PROGRAM ? PRG3_SIZE : 0);
    const end = start + size;
    const readable = (section.flags & SEGMENT_FLAGS.R) !== 0;
    const writable = (section.flags & SEGMENT_FLAGS.W) !== 0;
    const executable = (section.flags & SEGMENT_FLAGS.X) !== 0;
    const user = (section.flags & SEGMENT_FLAGS.U) !== 0;

    switch (section.type) {
      case SectionType.BIOS:
        if (!readable || !executable || writable || user) {
          this.errorAt('BIOS must be R/X and supervisor-only', 1, 1);
        }
        if (end > ROM_END) {
          this.errorAt('BIOS must fit in the 16 MiB firmware window', 1, 1);
        }
        break;
      case SectionType.BOOT:
      case SectionType.KERNEL:
        if (start < KERNEL_BASE || end > ADDRESS_SPACE_END) {
          this.errorAt(
            `${SectionType[section.type]} must be in kernel space`, 1, 1);
        }
        if (!readable || executable === writable || user) {
          this.errorAt(
            `${SectionType[section.type]} must be R/X or R/W and supervisor-only`, 1, 1);
        }
        break;
      case SectionType.PROGRAM:
        if (start < USER_BASE || start >= USER_END || end > USER_END) {
          this.errorAt('PROGRAM must be in user space', 1, 1);
        }
        if (!readable || !executable) {
          this.errorAt('PROGRAM must be R/X', 1, 1);
        }
        break;
      case SectionType.DATA:
        if (!readable || executable || user
            || (!withinRom(start, end) && !withinKernel(start, end))) {
          this.errorAt('DATA must be supervisor firmware/kernel data', 1, 1);
        }
        break;
      case SectionType.FONT:
      case SectionType.CONFIG:
        if (!readable || executable
            || (!withinRom(start, end)
              && !withinUser(start, end)
              && !withinKernel(start, end))) {
          this.errorAt(
            `${SectionType[section.type]} must be data in ROM, user or kernel space`, 1, 1);
        }
        break;
    }
  }

  private resolveReset(options: AssembleOptions): number {
    if (options.resetVector !== undefined) {
      return options.resetVector >>> 0;
    }
    if (options.entryPoint) {
      const symbol = this.labels.get(options.entryPoint);
      if (!symbol) {
        this.errorAt(`undefined entry point '${options.entryPoint}'`, 1, 1);
        return 0;
      }
      return symbol.address;
    }
    if (this.resetName) {
      const symbol = this.labels.get(this.resetName);
      if (!symbol) {
        this.errorAt(`undefined reset label '${this.resetName}'`, 1, 1);
        return 0;
      }
      return symbol.address;
    }
    const bios = this.sections.find(section => section.type === SectionType.BIOS);
    if (bios) {
      return bios.entryName
        ? this.labels.get(bios.entryName)?.address ?? bios.org
        : bios.org;
    }
    return 0;
  }

  private defineLabel(
    name: string,
    section: SectionBuilder,
    line: number,
    column: number,
  ): void {
    if (this.labels.has(name)) {
      this.errorAt(`duplicate label '${name}'`, line, column);
      return;
    }
    this.labels.set(name, {
      name,
      address: section.location,
      section: section.name,
      line,
    });
  }

  private parseFlags(statement: DirectiveStatement): number {
    let flags = 0;
    for (const name of statement.textArguments) {
      const value = SEGMENT_FLAGS[name as keyof typeof SEGMENT_FLAGS];
      if (value === undefined) {
        this.error(statement, `unknown segment flag '${name}'`);
      } else {
        flags |= value;
      }
    }
    return flags;
  }

  private startOf(section: SectionBuilder): number {
    return section.org + (section.type === SectionType.PROGRAM ? PRG3_SIZE : 0);
  }

  private symbolsOf(section: string): SymbolInfo[] {
    return [...this.labels.values()].filter(symbol => symbol.section === section);
  }

  private constValue(statement: DirectiveStatement, expr: Expr | undefined): number | undefined {
    if (!expr) {
      this.error(statement, `directive .${statement.name} requires an expression`);
      return undefined;
    }
    try {
      return evaluate(expr, name => this.labels.get(name)?.address);
    } catch (error) {
      this.error(statement, (error as Error).message);
      return undefined;
    }
  }

  private value(expr: Expr, statement: SourceLocation): number {
    try {
      return evaluate(expr, name => this.labels.get(name)?.address);
    } catch (error) {
      this.errorAt((error as Error).message, statement.line, statement.column);
      return 0;
    }
  }

  private error(statement: SourceLocation, message: string): void {
    this.errorAt(message, statement.line, statement.column);
  }

  private validateEntries(segments: readonly SegmentLayout[]): void {
    const bios = segments.find(segment => segment.section.type === SectionType.BIOS);
    if (!bios) {
      return;
    }
    if (this.resetVector < bios.section.org
        || this.resetVector >= bios.section.org + bios.bytes.length) {
      this.errorAt('reset vector must point inside the BIOS segment', 1, 1);
    }
    for (const segment of segments) {
      if (!isExecutable(segment.section.flags)) {
        continue;
      }
      if (segment.section.type === SectionType.PROGRAM) {
        const codeOffset = readU32(segment.bytes, 4);
        const codeEnd = readU32(segment.bytes, 8);
        const relative = segment.entryPoint - segment.section.org;
        if (relative < codeOffset || relative >= codeEnd) {
          this.errorAt(
            `entry point of '${segment.section.name}' must fall inside its code range`, 1, 1);
        }
      } else if (segment.entryPoint < segment.section.org
          || segment.entryPoint >= segment.section.org + segment.bytes.length) {
        this.errorAt(
          `entry point of '${segment.section.name}' must fall inside the segment`, 1, 1);
      }
    }
  }

  private errorAt(message: string, line: number, column: number): void {
    this.diagnostics.push({
      severity: 'error',
      message,
      line,
      column,
      file: this.sourceFile,
    });
  }

  private hasErrors(): boolean {
    return this.diagnostics.some(diagnostic => diagnostic.severity === 'error');
  }
}

interface SourceLocation {
  readonly line: number;
  readonly column: number;
}

function evaluate(expr: Expr, lookup: (name: string) => number | undefined): number {
  switch (expr.kind) {
    case 'number':
      return expr.value;
    case 'symbol': {
      const value = lookup(expr.name);
      if (value === undefined) {
        throw new Error(`undefined symbol '${expr.name}'`);
      }
      return value;
    }
    case 'unary': {
      const value = evaluate(expr.operand, lookup);
      return expr.operator === '-' ? -value : value;
    }
    case 'binary': {
      const left = evaluate(expr.left, lookup);
      const right = evaluate(expr.right, lookup);
      switch (expr.operator) {
        case '+':
          return left + right;
        case '-':
          return left - right;
        case '*':
          return left * right;
        case '/':
          if (right === 0) {
            throw new Error('division by zero in expression');
          }
          return Math.trunc(left / right);
        case '%':
          if (right === 0) {
            throw new Error('division by zero in expression');
          }
          return left % right;
      }
    }
  }
}

function isExecutable(flags: number): boolean {
  return (flags & SEGMENT_FLAGS.X) !== 0;
}

function withinRom(start: number, end: number): boolean {
  return start >= 0 && end <= ROM_END;
}

function withinUser(start: number, end: number): boolean {
  return start >= USER_BASE && end <= USER_END;
}

function withinKernel(start: number, end: number): boolean {
  return start >= KERNEL_BASE && end <= ADDRESS_SPACE_END;
}

function align(value: number, boundary: number): number {
  return Math.ceil(value / boundary) * boundary;
}

function overlaps(aStart: number, aSize: number, bStart: number, bSize: number): boolean {
  return aStart < bStart + bSize && bStart < aStart + aSize;
}

function writeU16(target: Uint8Array, offset: number, value: number): void {
  target[offset] = value & 0xff;
  target[offset + 1] = (value >>> 8) & 0xff;
}

function writeU32(target: Uint8Array, offset: number, value: number): void {
  target[offset] = value & 0xff;
  target[offset + 1] = (value >>> 8) & 0xff;
  target[offset + 2] = (value >>> 16) & 0xff;
  target[offset + 3] = (value >>> 24) & 0xff;
}

function readU32(source: Uint8Array, offset: number): number {
  return (source[offset] | (source[offset + 1] << 8)
    | (source[offset + 2] << 16) | (source[offset + 3] << 24)) >>> 0;
}

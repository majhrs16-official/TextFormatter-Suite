/**
 * iFlow `.flow` v2 disassembler.
 *
 * The runtime executes the whole code section (firmware ROM at 0x0000-0x0FFF
 * plus user code from 0x1000). This tool decodes only the user range, as PL-4
 * requires: the firmware is not a user program and its semantics belong to the
 * BIOS. Full exclusion of firmware/BIOS/bootloader/kernel/stdlib is an ISA v3
 * requirement; in v2 the only reliable cut is the user origin.
 *
 * The output is a linear listing plus a control-flow graph of basic blocks.
 * Decoding is purely mechanical: a `.flow` does not store the editor's rule
 * graph, so the CFG is what the bytes say, not a reconstruction of the nodes
 * that produced them.
 */
import {
  INSTRUCTION_SIZES,
  Opcode,
  PROGRAM_MAGIC,
  PROGRAM_VERSIONS,
  type ProgramImage,
} from './types';

const HEADER_SIZE = 20;
const HASH_SIZE = 32;
const USER_ORIGIN = 0x1000;

const REGISTER_NAMES = ['R0', 'R1', 'R2', 'R3', 'R4', 'R5', 'R6', 'R7', 'IP', 'SP', 'FP', 'FL'];

export class DisassemblerError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'DisassemblerError';
  }
}

export interface DisassemblyOptions {
  /** First address to decode. Defaults to the user origin (0x1000) when the image has firmware. */
  userStart?: number;
  /** Verify the trailing SHA-256. Defaults to true. */
  verifyHash?: boolean;
}

export type FlowKind = 'next' | 'jump' | 'branch' | 'call' | 'return' | 'halt';

export interface DisassembledInstruction {
  address: number;
  size: number;
  bytes: number[];
  mnemonic: string;
  operands: string[];
  text: string;
  /** Absolute target of a jump/branch/call, when the instruction has one. */
  target?: number;
  flow: FlowKind;
}

export interface BasicBlock {
  start: number;
  /** Exclusive end address. */
  end: number;
  instructions: number[];
  /** Successors that start another decoded block. */
  successors: number[];
  /** Control-flow targets outside the decoded user range (firmware helpers, padding, invalid). */
  exits: number[];
}

export interface DisassemblyDiagnostic {
  address: number;
  message: string;
}

export interface DisassemblyResult {
  formatVersion: number;
  isaVersion: number;
  minAbiVersion: number;
  minFirmwareVersion: number;
  entryPoint: number;
  codeLength: number;
  userStart: number;
  userEnd: number;
  instructions: DisassembledInstruction[];
  blocks: BasicBlock[];
  diagnostics: DisassemblyDiagnostic[];
}

/* ── parsing ────────────────────────────────────────────────────────────── */

function u16(data: Uint8Array, offset: number): number {
  return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
}

function u32(data: Uint8Array, offset: number): number {
  return (
    ((data[offset] & 0xff) << 24) |
    ((data[offset + 1] & 0xff) << 16) |
    ((data[offset + 2] & 0xff) << 8) |
    (data[offset + 3] & 0xff)
  );
}

async function sha256(data: Uint8Array): Promise<Uint8Array> {
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const buffer = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) as ArrayBuffer;
    return new Uint8Array(await crypto.subtle.digest('SHA-256', buffer));
  }
  if (typeof process !== 'undefined' && process.versions?.node) {
    const { createHash } = await import('crypto');
    return new Uint8Array(createHash('sha256').update(Buffer.from(data)).digest());
  }
  throw new DisassemblerError('SHA-256 no disponible en este entorno');
}

/**
 * Parses a `.flow` v2 image with the same checks as the Java
 * `ProgramImageParser`: magic, format/ISA/ABI/firmware versions, section
 * lengths, NUL-terminated capabilities, optional debug section and trailing
 * SHA-256 with no extra bytes.
 */
export async function parseProgramImage(data: Uint8Array, verifyHash = true): Promise<ProgramImage> {
  if (data.length < HEADER_SIZE) {
    throw new DisassemblerError('datos demasiado cortos para la cabecera');
  }
  const magic = u32(data, 0);
  if (magic !== PROGRAM_MAGIC) {
    throw new DisassemblerError('no es un .flow (magic 0x' + magic.toString(16).toUpperCase() + ')');
  }
  const formatVersion = data[4];
  const isaVersion = data[5];
  const minAbiVersion = data[6];
  const minFirmwareVersion = data[7];
  if (formatVersion !== PROGRAM_VERSIONS.format) {
    throw new DisassemblerError('formato ' + formatVersion + ' no soportado (esperado ' + PROGRAM_VERSIONS.format + ')');
  }
  if (isaVersion > PROGRAM_VERSIONS.isa) {
    throw new DisassemblerError('ISA ' + isaVersion + ' más nueva que ' + PROGRAM_VERSIONS.isa);
  }
  if (minAbiVersion !== PROGRAM_VERSIONS.minAbi) {
    throw new DisassemblerError('ABI ' + minAbiVersion + ' incompatible (se requiere ' + PROGRAM_VERSIONS.minAbi + ')');
  }
  if (minFirmwareVersion > PROGRAM_VERSIONS.minFirmware) {
    throw new DisassemblerError(
      'firmware ' + minFirmwareVersion + ' más nuevo que ' + PROGRAM_VERSIONS.minFirmware
    );
  }
  const entryPoint = u16(data, 8);
  const codeLength = u16(data, 10);
  const constantsLength = u16(data, 12);
  const capabilitiesCount = data[14];
  const debugLength = u16(data, 16);
  let offset = HEADER_SIZE;

  if (offset + codeLength > data.length) {
    throw new DisassemblerError('sección de código truncada');
  }
  const code = data.slice(offset, offset + codeLength);
  offset += codeLength;

  if (offset + constantsLength * 2 > data.length) {
    throw new DisassemblerError('sección de constantes truncada');
  }
  const constants: number[] = [];
  for (let i = 0; i < constantsLength; i++) {
    constants.push(u16(data, offset));
    offset += 2;
  }

  const capabilities: string[] = [];
  for (let i = 0; i < capabilitiesCount; i++) {
    if (offset >= data.length) {
      throw new DisassemblerError('sección de capacidades truncada');
    }
    let end = offset;
    while (end < data.length && data[end] !== 0) {
      end++;
    }
    if (end >= data.length) {
      throw new DisassemblerError('capacidad sin terminador NUL');
    }
    const capability = new TextDecoder().decode(data.slice(offset, end));
    if (capability.length === 0) {
      throw new DisassemblerError('capacidad vacía');
    }
    capabilities.push(capability);
    offset = end + 1;
  }

  let debugInfo = '';
  if (debugLength > 0) {
    if (offset + debugLength > data.length) {
      throw new DisassemblerError('sección de depuración truncada');
    }
    debugInfo = new TextDecoder().decode(data.slice(offset, offset + debugLength));
    offset += debugLength;
  }

  if (offset + HASH_SIZE > data.length) {
    throw new DisassemblerError('hash truncado');
  }
  const hash = data.slice(offset, offset + HASH_SIZE);
  offset += HASH_SIZE;
  if (offset !== data.length) {
    throw new DisassemblerError('bytes extra tras el hash: ' + (data.length - offset));
  }
  if (verifyHash) {
    const expected = await sha256(data.slice(0, data.length - HASH_SIZE));
    for (let i = 0; i < HASH_SIZE; i++) {
      if (expected[i] !== hash[i]) {
        throw new DisassemblerError('el SHA-256 del archivo no coincide');
      }
    }
  }

  return {
    formatVersion,
    isaVersion,
    minAbiVersion,
    minFirmwareVersion,
    entryPoint,
    code,
    constants,
    capabilities,
    hash,
    debugInfo,
  };
}

/* ── decoding ───────────────────────────────────────────────────────────── */

function registerName(index: number): string {
  return REGISTER_NAMES[index] ?? 'R?(' + index + ')';
}

function hex(value: number, digits: number): string {
  return '0x' + value.toString(16).toUpperCase().padStart(digits, '0');
}

interface Decoded {
  mnemonic: string;
  operands: string[];
  flow: FlowKind;
  target?: number;
  diagnostics: string[];
}

function decodeAt(code: Uint8Array, address: number): Decoded | null {
  const opcode = code[address];
  const size = INSTRUCTION_SIZES[opcode as Opcode] ?? 0;
  if (size === 0) {
    return null;
  }
  const diagnostics: string[] = [];
  const bytes = code.slice(address, address + size);
  if (bytes.length < size) {
    return { mnemonic: '.byte', operands: [hex(opcode, 2)], flow: 'halt', diagnostics: ['instrucción truncada'] };
  }
  const gpr = (value: number): string => {
    if (value > 7) {
      diagnostics.push('registro no válido R' + value);
    }
    return registerName(value);
  };

  switch (opcode) {
    case Opcode.LI:
      return {
        mnemonic: 'LI',
        operands: [gpr(bytes[1]), hex(u16(bytes, 2), 4)],
        flow: 'next',
        diagnostics,
      };
    case Opcode.MOV:
    case Opcode.ADD:
    case Opcode.SUB:
    case Opcode.MUL:
    case Opcode.DIV:
    case Opcode.MOD:
    case Opcode.AND:
    case Opcode.OR:
    case Opcode.XOR:
    case Opcode.SHL:
    case Opcode.SHR:
    case Opcode.SAR:
    case Opcode.CMP:
    case Opcode.CMPU: {
      const packed = bytes[1];
      return {
        mnemonic: Opcode[opcode],
        operands: [gpr((packed >> 4) & 0x0f), gpr(packed & 0x0f)],
        flow: 'next',
        diagnostics,
      };
    }
    case Opcode.LDBI:
    case Opcode.LDWI:
    case Opcode.STBI:
    case Opcode.STWI: {
      const packed = bytes[1];
      return {
        mnemonic: Opcode[opcode],
        operands: [gpr((packed >> 4) & 0x0f), '[' + gpr(packed & 0x0f) + ']'],
        flow: 'next',
        diagnostics,
      };
    }
    case Opcode.LDB:
    case Opcode.LDW:
    case Opcode.STB:
    case Opcode.STW:
      return {
        mnemonic: Opcode[opcode],
        operands: [gpr(bytes[1]), '[' + hex(u16(bytes, 2), 4) + ']'],
        flow: 'next',
        diagnostics,
      };
    case Opcode.COPY:
    case Opcode.MOVE:
      return {
        mnemonic: Opcode[opcode],
        operands: [hex(u16(bytes, 1), 4), hex(u16(bytes, 3), 4), hex(u16(bytes, 5), 4)],
        flow: 'next',
        diagnostics,
      };
    case Opcode.NOT:
      return { mnemonic: 'NOT', operands: [gpr(bytes[1] & 0x0f)], flow: 'next', diagnostics };
    case Opcode.JMP:
    case Opcode.JZ:
    case Opcode.JNZ:
    case Opcode.JC:
    case Opcode.JV:
    case Opcode.CALL: {
      const raw = u16(bytes, 1);
      const signed = raw & 0x8000 ? raw - 0x10000 : raw;
      const target = address + size + signed;
      const flow: FlowKind =
        opcode === Opcode.JMP ? 'jump' : opcode === Opcode.CALL ? 'call' : 'branch';
      return {
        mnemonic: Opcode[opcode],
        operands: [hex(target, 4)],
        flow,
        target,
        diagnostics,
      };
    }
    case Opcode.RET:
      return { mnemonic: 'RET', operands: [], flow: 'return', diagnostics };
    case Opcode.HOST:
      return { mnemonic: 'HOST', operands: [], flow: 'next', diagnostics };
    case Opcode.HALT:
      return { mnemonic: 'HALT', operands: [], flow: 'halt', diagnostics };
    case Opcode.TRAP:
      return { mnemonic: 'TRAP', operands: [hex(bytes[1], 2)], flow: 'halt', diagnostics };
    default:
      return null;
  }
}

function userRange(program: ProgramImage, options: DisassemblyOptions): { start: number; end: number } {
  const end = program.code.length;
  if (typeof options.userStart === 'number') {
    if (options.userStart < 0 || options.userStart > end) {
      throw new DisassemblerError('userStart fuera del código: ' + options.userStart);
    }
    return { start: options.userStart, end };
  }
  // Firmware builds occupy 0x0000-0x0FFF; a bare-metal image (--no-firmware)
  // starts at its own origin, which is at most one page (4 KiB).
  const start = end > USER_ORIGIN ? USER_ORIGIN : 0;
  return { start, end };
}

/** Decodes the user range and builds the basic-block CFG. */
export function decodeProgramImage(
  program: ProgramImage,
  options: DisassemblyOptions = {}
): DisassemblyResult {
  const { start, end } = userRange(program, options);
  const diagnostics: DisassemblyDiagnostic[] = [];
  const instructions: DisassembledInstruction[] = [];

  if (program.entryPoint >= program.code.length && program.code.length > 0) {
    diagnostics.push({
      address: program.entryPoint,
      message: 'entryPoint fuera del código',
    });
  }

  for (let address = start; address < end; ) {
    const decoded = decodeAt(program.code, address);
    if (decoded === null) {
      const opcode = program.code[address];
      diagnostics.push({
        address,
        message: 'opcode desconocido ' + hex(opcode, 2),
      });
      instructions.push({
        address,
        size: 1,
        bytes: [opcode],
        mnemonic: '.byte',
        operands: [hex(opcode, 2)],
        text: '.byte ' + hex(opcode, 2),
        flow: 'next',
      });
      address += 1;
      continue;
    }
    const size = INSTRUCTION_SIZES[program.code[address] as Opcode] ?? 1;
    for (const message of decoded.diagnostics) {
      diagnostics.push({ address, message });
    }
    const text =
      decoded.mnemonic + (decoded.operands.length > 0 ? ' ' + decoded.operands.join(', ') : '');
    instructions.push({
      address,
      size,
      bytes: Array.from(program.code.slice(address, address + size)),
      mnemonic: decoded.mnemonic,
      operands: decoded.operands,
      text,
      target: decoded.target,
      flow: decoded.flow,
    });
    if (address + size > end) {
      diagnostics.push({ address, message: 'instrucción truncada al final del código' });
      break;
    }
    address += size;
  }

  const byAddress = new Map<number, DisassembledInstruction>();
  for (const instruction of instructions) {
    byAddress.set(instruction.address, instruction);
  }
  const boundaries = new Set<number>([end]);
  for (const instruction of instructions) {
    boundaries.add(instruction.address);
  }

  for (const instruction of instructions) {
    if (typeof instruction.target !== 'number') {
      continue;
    }
    if (instruction.target < 0 || instruction.target >= program.code.length) {
      diagnostics.push({
        address: instruction.address,
        message: 'salto fuera del código: ' + hex(instruction.target, 4),
      });
    } else if (
      instruction.target >= start &&
      instruction.target < end &&
      !boundaries.has(instruction.target)
    ) {
      // A target inside the user range that is not an instruction boundary means
      // the linear decode is out of sync; a target below the user origin is a
      // firmware helper and is reported as an exit, not as an error.
      diagnostics.push({
        address: instruction.address,
        message: 'salto a un límite no decodificado: ' + hex(instruction.target, 4),
      });
    }
  }

  /* Leaders: the user start, every branch/call target inside the user range and
   * the instruction that follows a control-flow instruction. */
  const leaders = new Set<number>();
  if (instructions.length > 0) {
    leaders.add(instructions[0].address);
  }
  for (const instruction of instructions) {
    if (typeof instruction.target === 'number' && instruction.target >= start && instruction.target < end) {
      leaders.add(instruction.target);
    }
    const next = instruction.address + instruction.size;
    if (
      next < end &&
      (instruction.flow === 'jump' ||
        instruction.flow === 'branch' ||
        instruction.flow === 'call' ||
        instruction.flow === 'return' ||
        instruction.flow === 'halt')
    ) {
      leaders.add(next);
    }
  }
  const sorted = Array.from(leaders).sort((a, b) => a - b);

  const blocks: BasicBlock[] = [];
  const blockStart = new Map<number, BasicBlock>();
  for (let i = 0; i < sorted.length; i++) {
    const blockBegin = sorted[i];
    const blockEnd = i + 1 < sorted.length ? sorted[i + 1] : end;
    const blockInstructions: number[] = [];
    for (let address = blockBegin; address < blockEnd; ) {
      const instruction = byAddress.get(address);
      if (!instruction) {
        break;
      }
      blockInstructions.push(address);
      address += instruction.size;
    }
    const block: BasicBlock = {
      start: blockBegin,
      end: blockEnd,
      instructions: blockInstructions,
      successors: [],
      exits: [],
    };
    blocks.push(block);
    blockStart.set(blockBegin, block);
  }

  for (const block of blocks) {
    const lastAddress = block.instructions[block.instructions.length - 1];
    const last = lastAddress === undefined ? undefined : byAddress.get(lastAddress);
    if (!last) {
      continue;
    }
    const next = last.address + last.size;
    const addTarget = (target: number | undefined): void => {
      if (typeof target !== 'number') {
        return;
      }
      if (blockStart.has(target)) {
        if (!block.successors.includes(target)) {
          block.successors.push(target);
        }
      } else if (!block.exits.includes(target)) {
        block.exits.push(target);
      }
    };
    if (last.flow === 'jump') {
      addTarget(last.target);
    } else if (last.flow === 'branch' || last.flow === 'call') {
      addTarget(last.target);
      if (next < end) {
        addTarget(next);
      }
    } else if (last.flow === 'return' || last.flow === 'halt') {
      // no successors
    } else if (next < end) {
      addTarget(next);
    }
  }

  return {
    formatVersion: program.formatVersion,
    isaVersion: program.isaVersion,
    minAbiVersion: program.minAbiVersion,
    minFirmwareVersion: program.minFirmwareVersion,
    entryPoint: program.entryPoint,
    codeLength: program.code.length,
    userStart: start,
    userEnd: end,
    instructions,
    blocks,
    diagnostics,
  };
}

/** Parses and disassembles a `.flow` image. */
export async function disassemble(
  data: Uint8Array,
  options: DisassemblyOptions = {}
): Promise<DisassemblyResult> {
  const verifyHash = options.verifyHash !== false;
  const program = await parseProgramImage(data, verifyHash);
  return decodeProgramImage(program, options);
}

/** Renders a human-readable listing. */
export function formatListing(result: DisassemblyResult): string {
  const lines: string[] = [];
  lines.push(
    '; iFlow .flow v' +
      result.formatVersion +
      ' (ISA ' +
      result.isaVersion +
      ', ABI ' +
      result.minAbiVersion +
      ', firmware ' +
      result.minFirmwareVersion +
      ')'
  );
  lines.push(
    '; entry ' +
      hex(result.entryPoint, 4) +
      ', code ' +
      result.codeLength +
      ' bytes, user ' +
      hex(result.userStart, 4) +
      '..' +
      hex(result.userEnd, 4)
  );
  lines.push('');
  for (const instruction of result.instructions) {
    const bytes = instruction.bytes.map(b => b.toString(16).toUpperCase().padStart(2, '0')).join(' ');
    lines.push(
      hex(instruction.address, 4) +
        '  ' +
        bytes.padEnd(24, ' ') +
        '  ' +
        instruction.text
    );
  }
  lines.push('');
  lines.push('; CFG');
  for (const block of result.blocks) {
    const successors =
      block.successors.length > 0 ? block.successors.map(a => hex(a, 4)).join(', ') : '—';
    const exits = block.exits.length > 0 ? ' exits ' + block.exits.map(a => hex(a, 4)).join(', ') : '';
    lines.push(
      hex(block.start, 4) + '..' + hex(block.end, 4) + '  → ' + successors + exits
    );
  }
  if (result.diagnostics.length > 0) {
    lines.push('');
    lines.push('; diagnósticos');
    for (const diagnostic of result.diagnostics) {
      lines.push(hex(diagnostic.address, 4) + '  ' + diagnostic.message);
    }
  }
  return lines.join('\n') + '\n';
}

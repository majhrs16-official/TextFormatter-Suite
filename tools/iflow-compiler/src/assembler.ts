/**
 * iFlow VM Assembler - Two-pass assembler for .flowasm to .flow binary
 */
import {
  Opcode,
  Register,
  INSTRUCTION_SIZES,
  REGISTER_NAMES,
  ServiceId,
  Scope,
  PropertyId,
  Decision,
  ParsedInstruction,
  Operand,
  AssemblerOptions,
  AssemblerResult,
  Diagnostic,
  ProgramImage,
  Symbol as SymbolType,
} from './types';

interface LabelDef {
  name: string;
  address: number;
  line: number;
}

class Assembler {
  private options: AssemblerOptions;
  private diagnostics: Diagnostic[] = [];
  private labels = new Map<string, LabelDef>();
  private instructions: ParsedInstruction[] = [];
  private symbols = new Map<string, number>();
  private capabilities = new Set<string>();
  private currentAddress = 0;
  private currentLine = 0;
  private currentFile = '<input>';
  private currentSection = 'code';
  private constantPool: number[] = [];

  constructor(options: AssemblerOptions = {}) {
    this.options = options;
  }

  assemble(source: string, fileName = '<input>'): AssemblerResult {
    this.reset();
    this.currentFile = fileName;
    this.currentAddress = this.options.origin || 0;

    // First pass: parse instructions and collect labels
    this.firstPass(source);

    // Check for errors from first pass
    const firstPassErrors = this.diagnostics.filter(d => d.severity === 'error');
    if (firstPassErrors.length > 0) {
      return this.createResult(new Uint8Array(0));
    }

    // Second pass: resolve labels and generate bytecode
    const code = this.secondPass();

    // Check for errors from second pass
    const secondPassErrors = this.diagnostics.filter(d => d.severity === 'error');
    if (secondPassErrors.length > 0) {
      return this.createResult(new Uint8Array(0));
    }

    return this.createResult(new Uint8Array(code));
  }

  private reset() {
    this.diagnostics = [];
    this.labels.clear();
    this.instructions = [];
    this.symbols.clear();
    this.capabilities.clear();
    this.currentAddress = this.options.origin || 0;
    this.currentLine = 0;
    this.currentSection = 'code';
    this.constantPool = [];
  }

  private firstPass(source: string) {
    const lines = source.split('\n');
    this.currentAddress = this.options.origin || 0;

    for (let i = 0; i < lines.length; i++) {
      this.currentLine = i + 1;
      const line = lines[i];

      // Remove comments and trim
      const commentIndex = line.indexOf(';');
      const cleanLine = commentIndex >= 0 ? line.substring(0, commentIndex) : line;
      const trimmed = cleanLine.trim();

      if (!trimmed) continue;

      // Handle directives
      if (trimmed.startsWith('.')) {
        this.handleDirective(trimmed);
        continue;
      }

      // Check for label
      const labelMatch = trimmed.match(/^([a-zA-Z_][a-zA-Z0-9_]*):/);
      let instructionPart = trimmed;
      if (labelMatch) {
        const labelName = labelMatch[1];
        this.defineLabel(labelName, this.currentAddress, this.currentLine);
        instructionPart = trimmed.substring(labelMatch[0].length).trim();
      }

      if (instructionPart) {
        this.parseInstruction(instructionPart);
      }
    }
  }

  private secondPass(): number[] {
    const code: number[] = [];

    for (const instr of this.instructions) {
      // Sync currentAddress to instruction's stored address (handles directives that changed address in first pass)
      this.currentAddress = instr.address;

      // Emit opcode
      code.push(instr.opcode);
      this.currentAddress++;

      // Emit operands
      this.emitOperands(instr, code);
      
      // Advance currentAddress by remaining instruction bytes (size - 1 for opcode)
      this.currentAddress += instr.size - 1;
    }
    return code;
  }

  private handleDirective(line: string) {
    const parts = line.split(/\s+/);
    const directive = parts[0].toLowerCase();

    switch (directive) {
      case '.section':
      case '.seg':
        this.currentSection = parts[1] || 'code';
        break;

      case '.entry':
        this.options.entryPoint = parts[1];
        break;

      case '.origin':
      case '.org':
        this.currentAddress = this.parseNumber(parts[1] || '0');
        break;

      case '.capability':
      case '.cap':
        if (parts[1]) {
          this.capabilities.add(parts[1]);
        }
        break;

      case '.align':
        const align = this.parseNumber(parts[1] || '2');
        while (this.currentAddress % align !== 0) {
          this.currentAddress++;
        }
        break;

      case '.const':
        if (parts.length >= 3) {
          const name = parts[1];
          const value = this.parseNumber(parts[2]);
          // Define as a label/equate for use in instructions
          this.defineLabel(name, value, this.currentLine);
          // Also add to constant pool
          this.constantPool.push(value);
          this.symbols.set(name, this.constantPool.length - 1);
        }
        break;

      case '.equ':
      case '.set':
      case '=':
        if (parts.length >= 3) {
          const name = parts[1];
          const value = this.parseNumber(parts[2]);
          this.defineLabel(name, value, this.currentLine);
        }
        break;

      case '.end':
        // End of assembly - ignore
        break;

      default:
        this.addDiagnostic('warning', `Unknown directive: ${directive}`, this.currentLine, 0);
    }
  }

  private defineLabel(name: string, address: number, line: number) {
    if (this.labels.has(name)) {
      this.addDiagnostic('error', `Duplicate label: ${name}`, line, 0);
    } else {
      this.labels.set(name, { name, address, line });
      this.symbols.set(name, address);
    }
  }

  private parseInstruction(line: string) {
    const parts = this.tokenize(line);
    if (parts.length === 0) return;

    const mnemonic = parts[0].toUpperCase();
    const opcode = this.getOpcode(mnemonic);

    if (opcode === undefined) {
      this.addDiagnostic('error', `Unknown instruction: ${mnemonic}`, this.currentLine, 0);
      return;
    }

    const operands = this.parseOperands(parts.slice(1), opcode);
    const size = INSTRUCTION_SIZES[opcode] || this.calculateSize(opcode, operands);

    const instruction: ParsedInstruction = {
      opcode,
      operands,
      address: this.currentAddress,
      size,
      line: this.currentLine,
      source: line,
    };

    this.instructions.push(instruction);
    this.currentAddress += size;
  }

  private tokenize(line: string): string[] {
    // Split by comma or whitespace, keeping quoted strings together
    // Also handle simple expressions like LABEL + OFFSET
    const tokens: string[] = [];
    let current = '';
    let inQuotes = false;
    let quoteChar = '';

    for (let i = 0; i < line.length; i++) {
      const char = line[i];

      if ((char === '"' || char === "'") && !inQuotes) {
        inQuotes = true;
        quoteChar = char;
        current += char;
      } else if (char === quoteChar && inQuotes) {
        inQuotes = false;
        current += char;
      } else if ((char === ',' || /\s/.test(char)) && !inQuotes) {
        if (current) {
          tokens.push(current);
          current = '';
        }
      } else if ((char === '+' || char === '-') && !inQuotes) {
        // Handle +/- as part of expression (e.g., LABEL+OFFSET)
        if (current && /[a-zA-Z0-9_]$/.test(current)) {
          current += char;
        } else {
          if (current) {
            tokens.push(current);
            current = '';
          }
          current += char;
        }
      } else {
        current += char;
      }
    }

    if (current) tokens.push(current);
    
    // Post-process: merge tokens like "LABEL+OFFSET" into single token
    const merged: string[] = [];
    for (let i = 0; i < tokens.length; i++) {
      const token = tokens[i];
      if (i > 0 && (token === '+' || token === '-') && 
          /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(tokens[i-1]) &&
          i + 1 < tokens.length && this.isNumber(tokens[i+1])) {
        // Merge: previous_label + number
        merged[merged.length - 1] = tokens[i-1] + token + tokens[i+1];
        i++; // Skip the number token
      } else {
        merged.push(token);
      }
    }
    
    // Debug
    // console.error(`TOKENS: ${JSON.stringify(merged)}`);
    
    return merged.map(t => t.replace(/^,|,$/g, '').trim()).filter(t => t);
  }

  private getOpcode(mnemonic: string): Opcode | undefined {
    return Opcode[mnemonic as keyof typeof Opcode];
  }

  private parseOperands(tokens: string[], opcode: Opcode): Operand[] {
    const operands: Operand[] = [];

    for (const token of tokens) {
      // Register
      if (REGISTER_NAMES[token.toUpperCase()] !== undefined) {
        operands.push({ type: 'reg', value: REGISTER_NAMES[token.toUpperCase()] });
        continue;
      }

      // Packed RR format (e.g., "R1,R2")
      if (token.includes(',')) {
        const [rd, rs] = token.split(',').map(s => s.trim().toUpperCase());
        if (REGISTER_NAMES[rd] !== undefined && REGISTER_NAMES[rs] !== undefined) {
          operands.push({ type: 'packed_rr', rd: REGISTER_NAMES[rd], rs: REGISTER_NAMES[rs] });
          continue;
        }
      }

      // Label reference (including LABEL+OFFSET expressions)
      if (/^[a-zA-Z_][a-zA-Z0-9_]*([+-]\d+)?$/.test(token) && !this.isNumber(token)) {
        operands.push({ type: 'label', name: token });
        continue;
      }

      // Number (immediate or address)
      const value = this.parseNumber(token);
      if (value >= -128 && value <= 255) {
        operands.push({ type: 'imm8', value: value & 0xFF });
      } else {
        operands.push({ type: 'imm16', value: value & 0xFFFF });
      }
    }

    return operands;
  }

  private isNumber(token: string): boolean {
    return /^0x[0-9a-fA-F]+$/.test(token) || /^\d+$/.test(token) || /^-?\d+$/.test(token);
  }

  private parseNumber(token: string): number {
    if (token.startsWith('0x') || token.startsWith('0X')) {
      return parseInt(token.substring(2), 16);
    }
    return parseInt(token, 10);
  }

  private calculateSize(opcode: Opcode, operands: Operand[]): number {
    return INSTRUCTION_SIZES[opcode] || 1;
  }

  private emitOperands(instr: ParsedInstruction, code: number[]) {
    const { opcode, operands } = instr;

    switch (opcode) {
      case Opcode.LI: { // LI rd, imm16
        const rd = this.getRegOperand(operands, 0);
        const imm = this.getImm16Operand(operands, 1);
        code.push(rd);
        code.push((imm >> 8) & 0xFF);
        code.push(imm & 0xFF);
        break;
      }

      case Opcode.MOV: { // MOV rd, rs (packed)
        const packed = this.getPackedRROperand(operands);
        if (packed) {
          code.push((packed.rd << 4) | packed.rs);
        } else {
          const rd = this.getRegOperand(operands, 0);
          const rs = this.getRegOperand(operands, 1);
          code.push((rd << 4) | rs);
        }
        break;
      }

      case Opcode.LDB:
      case Opcode.LDW:
      case Opcode.STB:
      case Opcode.STW: { // MEM rd/rs, vaddr16
        const reg = this.getRegOperand(operands, 0);
        const vaddr = this.getVaddrOperand(operands, 1);
        code.push(reg);
        code.push((vaddr >> 8) & 0xFF);
        code.push(vaddr & 0xFF);
        break;
      }

      case Opcode.COPY:
      case Opcode.MOVE: { // BLOCK srcVaddr16, dstVaddr16, len16 (8 bytes: opcode + src16 + dst16 + len16 + padding)
        const src = this.getVaddrOperand(operands, 0);
        const dst = this.getVaddrOperand(operands, 1);
        const len = this.getImm16Operand(operands, 2);
        code.push((src >> 8) & 0xFF, src & 0xFF);
        code.push((dst >> 8) & 0xFF, dst & 0xFF);
        code.push((len >> 8) & 0xFF, len & 0xFF);
        code.push(0x00); // Padding byte to make 8 bytes total (1 opcode + 7 operands = 8)
        break;
      }

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
      case Opcode.CMPU: { // RR rd, rs (packed)
        const packed = this.getPackedRROperand(operands);
        if (packed) {
          code.push((packed.rd << 4) | packed.rs);
        } else {
          const rd = this.getRegOperand(operands, 0);
          const rs = this.getRegOperand(operands, 1);
          code.push((rd << 4) | rs);
        }
        break;
      }

      case Opcode.NOT: { // R rd (2 bytes: opcode + reg)
        const rd = this.getRegOperand(operands, 0);
        code.push(rd & 0x0F);
        break;
      }

      case Opcode.JMP:
      case Opcode.JZ:
      case Opcode.JNZ:
      case Opcode.JC:
      case Opcode.JV:
      case Opcode.CALL: { // BR16 offset16
        const offset = this.getBranchOffset(operands, 0, instr.address, opcode);
        code.push((offset >> 8) & 0xFF);
        code.push(offset & 0xFF);
        break;
      }

      case Opcode.RET: { // R rs (2 bytes: opcode + rs<<4)
        const rs = this.getRegOperand(operands, 0);
        code.push((rs & 0x0F) << 4);
        break;
      }

      case Opcode.HOST: { // HOST (2 bytes: opcode + dummy)
        // Service ID is passed in R0, not in instruction
        // Emit dummy byte to match instructionSize(2)
        code.push(0);
        break;
      }

      case Opcode.HALT: {
        // No operands
        break;
      }

      case Opcode.TRAP: { // TRAP code8
        const code8 = this.getImm8Operand(operands, 0);
        code.push(code8);
        break;
      }
    }
  }

  private getRegOperand(operands: Operand[], index: number): number {
    const op = operands[index];
    if (op && op.type === 'reg') return op.value;
    return 0;
  }

  private getImm8Operand(operands: Operand[], index: number): number {
    const op = operands[index];
    if (op && (op.type === 'imm8' || op.type === 'imm16')) return op.value & 0xFF;
    return 0;
  }

  private getImm16Operand(operands: Operand[], index: number): number {
    const op = operands[index];
    if (op && (op.type === 'imm16' || op.type === 'imm8')) return op.value & 0xFFFF;
    if (op && op.type === 'label') {
      // Handle LABEL+OFFSET or LABEL-OFFSET format
      const match = op.name.match(/^([a-zA-Z_][a-zA-Z0-9_]*)([+-]\d+)?$/);
      if (match) {
        const labelName = match[1];
        const offsetStr = match[2] || '0';
        const offset = parseInt(offsetStr, 10);
        const label = this.labels.get(labelName);
        if (label) return (label.address + offset) & 0xFFFF;
        this.addDiagnostic('error', `Undefined label: ${labelName}`, 0, 0);
        return 0;
      }
      // Fallback for plain labels
      const label = this.labels.get(op.name);
      if (label) return label.address & 0xFFFF;
      this.addDiagnostic('error', `Undefined label: ${op.name}`, 0, 0);
      return 0;
    }
    return 0;
  }

  private getVaddrOperand(operands: Operand[], index: number): number {
    return this.getImm16Operand(operands, index);
  }

  private getPackedRROperand(operands: Operand[]): { rd: number; rs: number } | null {
    for (const op of operands) {
      if (op.type === 'packed_rr') return { rd: op.rd, rs: op.rs };
    }
    return null;
  }

  private getBranchOffset(operands: Operand[], index: number, currentAddr: number, opcode: Opcode): number {
    const op = operands[index];
    if (!op) return 0;

    if (op.type === 'label') {
      const label = this.labels.get(op.name);
      if (label) {
        // Calculate relative offset from next instruction
        const nextAddr = currentAddr + (INSTRUCTION_SIZES[opcode] || 3);
        return (label.address - nextAddr) & 0xFFFF;
      }
      this.addDiagnostic('error', `Undefined label: ${op.name}`, 0, 0);
      return 0;
    }

    if (op.type === 'imm16' || op.type === 'imm8') {
      return (op.value - (currentAddr + 3)) & 0xFFFF;
    }

    return 0;
  }

  private createResult(program: Uint8Array): AssemblerResult {
    const programImage: ProgramImage = {
      formatVersion: 1,
      isaVersion: 1,
      minAbiVersion: 1,
      minFirmwareVersion: 1,
      entryPoint: this.getEntryPointAddress(),
      code: program,
      constants: this.constantPool,
      capabilities: Array.from(this.capabilities),
      hash: new Uint8Array(32), // Placeholder for SHA-256
      debugInfo: this.options.debug ? this.generateDebugInfo() : undefined,
    };

    const symbolArray: SymbolType[] = [];
    for (const [name, address] of this.symbols) {
      symbolArray.push({ name, address, type: 'code' });
    }

    return {
      program: programImage,
      symbols: symbolArray,
      diagnostics: this.diagnostics,
    };
  }

  private getEntryPointAddress(): number {
    if (this.options.entryPoint) {
      const label = this.labels.get(this.options.entryPoint);
      if (label) return label.address;
    }
    return this.options.origin || 0;
  }

  private generateDebugInfo(): string {
    let info = '';
    for (const instr of this.instructions) {
      info += `${instr.address.toString(16).padStart(4, '0')}: ${instr.source}\n`;
    }
    for (const [name, address] of this.symbols) {
      info += `SYM ${name} = 0x${address.toString(16).padStart(4, '0')}\n`;
    }
    return info;
  }

  public serializeProgramImage(image: ProgramImage): Uint8Array {
    // Simple serialization: header + code + constants + capabilities
    const parts: number[] = [];

    // Header (16 bytes)
    parts.push(image.formatVersion & 0xFF);
    parts.push(image.isaVersion & 0xFF);
    parts.push(image.minAbiVersion & 0xFF);
    parts.push(image.minFirmwareVersion & 0xFF);
    parts.push((image.entryPoint >> 8) & 0xFF);
    parts.push(image.entryPoint & 0xFF);
    parts.push((image.code.length >> 8) & 0xFF);
    parts.push(image.code.length & 0xFF);
    parts.push((image.constants.length >> 8) & 0xFF);
    parts.push(image.constants.length & 0xFF);
    parts.push(image.capabilities.length & 0xFF);
    // 5 bytes padding
    parts.push(0, 0, 0, 0, 0);

    // Code
    parts.push(...Array.from(image.code));

    // Constants
    for (const c of image.constants) {
      parts.push((c >> 8) & 0xFF, c & 0xFF);
    }

    // Capabilities (null-terminated strings)
    for (const cap of image.capabilities) {
      for (const ch of cap) {
        parts.push(ch.charCodeAt(0));
      }
      parts.push(0);
    }

    // Hash (32 bytes)
    parts.push(...Array.from(image.hash));

    return new Uint8Array(parts);
  }

  private addDiagnostic(severity: 'error' | 'warning' | 'info', message: string, line: number, column: number) {
    this.diagnostics.push({
      severity,
      message,
      line: line || this.currentLine,
      column,
      file: this.currentFile,
    });
  }
}

export { Assembler };
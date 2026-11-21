/**
 * iFlow v3 assembler types.
 *
 * This module mirrors the frozen encoding of ADR-037 §3/§4 and the ROM layout
 * of §8. It is deliberately independent from the v2 assembler: the v3 module
 * never imports v2 opcodes, operands or section types.
 */

export enum InstructionForm {
  REGISTER = 0,
  IMMEDIATE = 1,
  LOAD = 2,
  STORE = 3,
}

export enum OperandSize {
  BYTE = 0,
  HALF = 1,
  WORD = 2,
}

export const FORM_LENGTH: Readonly<Record<InstructionForm, number>> = {
  [InstructionForm.REGISTER]: 3,
  [InstructionForm.IMMEDIATE]: 7,
  [InstructionForm.LOAD]: 7,
  [InstructionForm.STORE]: 7,
};

export interface OpcodeDef {
  readonly mnemonic: string;
  readonly family: number;
  readonly operation: number;
  readonly forms: readonly InstructionForm[];
  readonly usesRsAsOperand?: boolean;
}

function op(
  mnemonic: string,
  family: number,
  operation: number,
  forms: readonly InstructionForm[],
  usesRsAsOperand = false,
): OpcodeDef {
  return { mnemonic, family, operation, forms, usesRsAsOperand };
}

const RR = InstructionForm.REGISTER;
const RI = InstructionForm.IMMEDIATE;
const RD = InstructionForm.LOAD;
const WR = InstructionForm.STORE;

/** The complete v3 opcode table. Reserved combinations are absent. */
export const OPCODES: Readonly<Record<string, OpcodeDef>> = Object.freeze({
  MOV: op('MOV', 0, 0, [RR, RI, RD, WR]),
  LEA: op('LEA', 0, 1, [RD]),
  PUSH: op('PUSH', 0, 2, [RR]),
  POP: op('POP', 0, 3, [RR]),

  ADD: op('ADD', 1, 0, [RR, RI]),
  ADC: op('ADC', 1, 1, [RR, RI]),
  SUB: op('SUB', 1, 2, [RR, RI]),
  AND: op('AND', 1, 3, [RR, RI]),
  OR: op('OR', 1, 4, [RR, RI]),
  XOR: op('XOR', 1, 5, [RR, RI]),
  SHL: op('SHL', 1, 6, [RR, RI]),
  SHR: op('SHR', 1, 7, [RR, RI]),
  SAR: op('SAR', 1, 8, [RR, RI]),
  MUL: op('MUL', 1, 9, [RR, RI]),
  DIV: op('DIV', 1, 10, [RR, RI]),
  DIVU: op('DIVU', 1, 11, [RR, RI]),
  MOD: op('MOD', 1, 12, [RR, RI]),
  MODU: op('MODU', 1, 13, [RR, RI]),
  CMP: op('CMP', 1, 14, [RR, RI]),
  TEST: op('TEST', 1, 15, [RR, RI]),

  JMP: op('JMP', 2, 0, [RR, RI]),
  JCC: op('JCC', 2, 1, [RI]),
  CALL: op('CALL', 2, 2, [RR, RI]),
  RET: op('RET', 2, 3, [RR]),
  ECALL: op('ECALL', 2, 4, [RR]),
  ERET: op('ERET', 2, 5, [RR]),
  HALT: op('HALT', 2, 6, [RR]),
  BREAK: op('BREAK', 2, 7, [RR]),
  NOP: op('NOP', 2, 8, [RR]),

  HOST: op('HOST', 3, 0, [RR]),
  RDCYCLE: op('RDCYCLE', 3, 1, [RR]),
  RDTIME: op('RDTIME', 3, 2, [RR]),
  CSRRW: op('CSRRW', 3, 3, [RI]),
  CSRRS: op('CSRRS', 3, 4, [RI]),
  CSRRC: op('CSRRC', 3, 5, [RI]),
  CSRR: op('CSRR', 3, 6, [RI]),
  CSRW: op('CSRW', 3, 7, [RI]),
  SFENCE_VMA: op('SFENCE.VMA', 3, 8, [RR]),
  FENCE: op('FENCE', 3, 9, [RR]),
  LR_W: op('LR.W', 3, 10, [RD]),
  SC_W: op('SC.W', 3, 11, [WR], true),
  AMOADD_W: op('AMOADD.W', 3, 12, [WR], true),
  AMOSWAP_W: op('AMOSWAP.W', 3, 13, [WR], true),
  AMOAND_W: op('AMOAND.W', 3, 14, [WR], true),
  AMOOR_W: op('AMOOR.W', 3, 15, [WR], true),
});

export function findOpcode(mnemonic: string): OpcodeDef | undefined {
  const upper = mnemonic.toUpperCase();
  return OPCODES[upper] ?? OPCODES[upper.replace(/\./g, '_')];
}

/** `Jcc` condition aliases. The value is the `rs` condition code. */
export const CONDITIONS: Readonly<Record<string, number>> = Object.freeze({
  EQ: 0,
  Z: 0,
  NE: 1,
  NZ: 1,
  CS: 2,
  C: 2,
  B: 2,
  CC: 3,
  NC: 3,
  AE: 3,
  MI: 4,
  PL: 5,
  VS: 6,
  VC: 7,
  HI: 8,
  LS: 9,
  GE: 10,
  LT: 11,
  GT: 12,
  LE: 13,
  ALWAYS: 14,
  NEVER: 15,
});

/** Mnemonic aliases that expand to `Jcc <condition>, target`. */
export const JCC_ALIASES: Readonly<Record<string, number>> = Object.freeze({
  JE: 0,
  JZ: 0,
  JNE: 1,
  JNZ: 1,
  JCS: 2,
  JC: 2,
  JB: 2,
  JCC: 3,
  JNC: 3,
  JAE: 3,
  JMI: 4,
  JPL: 5,
  JVS: 6,
  JVC: 7,
  JHI: 8,
  JLS: 9,
  JGE: 10,
  JLT: 11,
  JGT: 12,
  JLE: 13,
  JALWAYS: 14,
  JNEVER: 15,
});

/** Capability names accepted by the v3 ROM parser (ADR-037 §7). */
export const BASELINE_CAPABILITIES: readonly string[] = Object.freeze([
  'iflow.property.read',
  'iflow.property.write',
  'iflow.permission.check',
  'iflow.route.decision',
  'iflow.transform.apply',
  'iflow.message.id',
  'sync.surface',
  'sync.invoke',
]);

export function isKnownCapability(name: string): boolean {
  if (BASELINE_CAPABILITIES.includes(name)) {
    return true;
  }
  const parts = name.split('.');
  if (parts.length < 3 || parts.some(part => part.length === 0)) {
    return false;
  }
  return parts[0] === 'sync' || parts[0] === 'module';
}

/** CSR addresses from ADR-037 §4. */
export const CSRS: Readonly<Record<string, number>> = Object.freeze({
  STATUS: 0x100,
  EPC: 0x101,
  CAUSE: 0x102,
  BADVADDR: 0x103,
  TVEC: 0x104,
  PTBR: 0x105,
  SCRATCH: 0x106,
  PIDENT: 0x107,
  MIDENT: 0x108,
  FLAGS: 0x109,
  COREID: 0x10a,
});

export enum SectionType {
  BIOS = 1,
  BOOT = 2,
  KERNEL = 3,
  PROGRAM = 4,
  DATA = 5,
  FONT = 6,
  CONFIG = 7,
}

export const SECTION_TYPE_NAMES: Readonly<Record<string, SectionType>> = Object.freeze({
  bios: SectionType.BIOS,
  boot: SectionType.BOOT,
  kernel: SectionType.KERNEL,
  program: SectionType.PROGRAM,
  data: SectionType.DATA,
  font: SectionType.FONT,
  config: SectionType.CONFIG,
});

export const SEGMENT_FLAGS = Object.freeze({
  R: 1,
  W: 2,
  X: 4,
  U: 8,
});

export interface Diagnostic {
  readonly severity: 'error' | 'warning' | 'info';
  readonly message: string;
  readonly line: number;
  readonly column: number;
  readonly file: string;
}

export class AssemblyError extends Error {
  readonly diagnostics: readonly Diagnostic[];

  constructor(diagnostics: readonly Diagnostic[]) {
    super(diagnostics.map(d => d.message).join('; '));
    this.name = 'AssemblyError';
    this.diagnostics = diagnostics;
  }
}

export interface SymbolInfo {
  readonly name: string;
  readonly address: number;
  readonly section: string;
  readonly line: number;
}

export interface SegmentResult {
  readonly name: string;
  readonly type: SectionType;
  readonly flags: number;
  readonly loadAddress: number;
  readonly entryPoint: number;
  readonly bytes: Uint8Array;
  readonly memSize: number;
  readonly symbols: readonly SymbolInfo[];
}

export interface RomResult {
  readonly bytes: Uint8Array;
  readonly resetVector: number;
  readonly segments: readonly SegmentResult[];
  readonly symbols: readonly SymbolInfo[];
  readonly diagnostics: readonly Diagnostic[];
}

export interface AssembleOptions {
  readonly resetVector?: number;
  readonly entryPoint?: string;
  readonly maxRomSize?: number;
}

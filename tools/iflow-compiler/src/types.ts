/**
 * iFlow VM Opcode Definitions
 * Matches the Java ISA specification exactly
 */
export enum Opcode {
  // Data transfer (0x00-0x0F)
  LI   = 0x00, // RI16: Load Immediate 16-bit
  MOV  = 0x01, // RR: Move register
  LDB  = 0x02, // MEM: Load Byte
  LDW  = 0x03, // MEM: Load Word
  STB  = 0x04, // MEM: Store Byte
  STW  = 0x05, // MEM: Store Word
  COPY = 0x06, // BLOCK: Copy non-overlapping
  MOVE = 0x07, // BLOCK: Move (allows overlap)

  // Indirect memory access (ISA v2, 0x08-0x0B)
  LDBI = 0x08, // RR: Load Byte indirect  Rd, [Rs]
  LDWI = 0x09, // RR: Load Word indirect  Rd, [Rs]
  STBI = 0x0A, // RR: Store Byte indirect Rv, [Ra]
  STWI = 0x0B, // RR: Store Word indirect Rv, [Ra]

  // Arithmetic (0x20-0x27)
  ADD  = 0x20, // RR: Add
  SUB  = 0x21, // RR: Subtract
  MUL  = 0x22, // RR: Multiply
  DIV  = 0x23, // RR: Divide (signed)
  MOD  = 0x24, // RR: Modulo (signed)

  // Logic (0x28-0x2B)
  AND  = 0x28, // RR: Bitwise AND
  OR   = 0x29, // RR: Bitwise OR
  XOR  = 0x2A, // RR: Bitwise XOR
  NOT  = 0x2B, // R: Bitwise NOT

  // Shifts (0x2C-0x2E)
  SHL  = 0x2C, // RR: Shift Left Logical
  SHR  = 0x2D, // RR: Shift Right Logical
  SAR  = 0x2E, // RR: Shift Right Arithmetic

  // Comparison (0x30-0x31)
  CMP  = 0x30, // RR: Compare (signed)
  CMPU = 0x31, // RR: Compare (unsigned)

  // Flow control (0x40-0x4F)
  JMP  = 0x40, // BR16: Jump relative
  JZ   = 0x41, // BR16: Jump if Zero
  JNZ  = 0x42, // BR16: Jump if Not Zero
  JC   = 0x43, // BR16: Jump if Carry
  JV   = 0x44, // BR16: Jump if Overflow
  CALL = 0x48, // BR16: Call subroutine
  RET  = 0x49, // R: Return (1 byte, no operand)

  // Services (0x60-0x6F)
  HOST = 0x60, // HOST: Generic host service call

  // Control (0x68-0x6F)
  HALT = 0x68, // R: Halt execution (normal)
  TRAP = 0x69, // R+imm8: Trap with code
}

/**
 * Instruction sizes in bytes
 */
export const INSTRUCTION_SIZES: Record<Opcode, number> = {
  [Opcode.LI]: 4,       // RI16
  [Opcode.MOV]: 2,      // RR (packed)
  [Opcode.LDB]: 4,      // MEM: opcode + reg + vaddr16
  [Opcode.LDW]: 4,
  [Opcode.STB]: 4,
  [Opcode.STW]: 4,
  [Opcode.COPY]: 8,     // BLOCK: srcVaddr16 + dstVaddr16 + len16
  [Opcode.MOVE]: 8,
  [Opcode.LDBI]: 2,     // RR (packed, indirect addressing)
  [Opcode.LDWI]: 2,
  [Opcode.STBI]: 2,
  [Opcode.STWI]: 2,
  [Opcode.ADD]: 2,
  [Opcode.SUB]: 2,
  [Opcode.MUL]: 2,
  [Opcode.DIV]: 2,
  [Opcode.MOD]: 2,
  [Opcode.AND]: 2,
  [Opcode.OR]: 2,
  [Opcode.XOR]: 2,
  [Opcode.NOT]: 2,
  [Opcode.SHL]: 2,
  [Opcode.SHR]: 2,
  [Opcode.SAR]: 2,
  [Opcode.CMP]: 2,
  [Opcode.CMPU]: 2,
  [Opcode.JMP]: 3,
  [Opcode.JZ]: 3,
  [Opcode.JNZ]: 3,
  [Opcode.JC]: 3,
  [Opcode.JV]: 3,
  [Opcode.CALL]: 3,
  [Opcode.RET]: 1,      // R (no operand)
  [Opcode.HOST]: 2,
  [Opcode.HALT]: 1,
  [Opcode.TRAP]: 2,
};

/**
 * Register indices
 */
export enum Register {
  R0 = 0, R1 = 1, R2 = 2, R3 = 3,
  R4 = 4, R5 = 5, R6 = 6, R7 = 7,
  IP = 8, SP = 9, FP = 10, FL = 11,
}

/** Returns true if register is a general-purpose register (R0-R7). */
export function isGPR(reg: number): boolean {
  return reg >= Register.R0 && reg <= Register.R7;
}

/**
 * Service IDs (matching Java HostServiceRegistry)
 */
export enum ServiceId {
  // Property services
  PROPERTY_READ  = 0x0100,
  PROPERTY_WRITE = 0x0101,
  PROPERTY_EXISTS = 0x0102,

  // Permission services
  PERMISSION_CHECK = 0x0200,
  PERMISSION_QUERY = 0x0201,

  // Routing services
  ROUTE_DECISION = 0x0300,
  ROUTE_REDIRECT = 0x0301,
  ROUTE_RATE_LIMIT = 0x0302,

  // State services
  STATE_READ = 0x0500,
  STATE_WRITE = 0x0501,

  // Channel services
  CHANNEL_RESOLVE = 0x0600,
  CHANNEL_POLICY = 0x0601,

  // Message services
  MESSAGE_CLONE = 0x0700,
  MESSAGE_ID = 0x0701,
}

/**
 * Property scopes
 */
export enum Scope {
  MESSAGE = 0x01,
  EMITTER = 0x02,
  RECIPIENT = 0x03,
  CHANNEL = 0x04,
  SERVER = 0x05,
}

/**
 * Property IDs
 */
export enum PropertyId {
  CONTENT = 0x0010,
  CHANNEL = 0x0011,
  DIRECTION = 0x0012,
  TYPE = 0x0013,
  LANG_SOURCE = 0x0014,
  LANG_TARGET = 0x0015,
  COLOR_MODE = 0x0016,
  FORMAT_PAPI = 0x0017,
  SLEEP_MILLIS = 0x0018,
  SOUNDS = 0x0019,
  CANCELLED = 0x001A,
  PROCESSED = 0x001B,
  TRANSLATE = 0x001C,
}

/**
 * Routing decision codes
 */
export enum Decision {
  LOG = 0,
  DROP = 1,
  REJECT = 2,
  REDIRECT = 3,
  CHANNEL_REDIRECT = 4,
  RATE_LIMIT = 5,
  CONTINUE = 6,
}

/**
 * Register names for parser
 */
export const REGISTER_NAMES: Record<string, number> = {
  'R0': 0, 'R1': 1, 'R2': 2, 'R3': 3,
  'R4': 4, 'R5': 5, 'R6': 6, 'R7': 7,
  'IP': 8, 'SP': 9, 'FP': 10, 'FL': 11,
};

/**
 * Parsed instruction representation
 */
export interface ParsedInstruction {
  opcode: Opcode;
  operands: Operand[];
  address: number;
  size: number;
  line: number;
  source: string;
}

/**
 * Operand types
 */
export type Operand =
  | { type: 'reg'; value: number }
  | { type: 'imm8'; value: number; rawValue?: number }
  | { type: 'imm16'; value: number; rawValue?: number }
  | { type: 'vaddr'; value: number }
  | { type: 'label'; name: string }
  | { type: 'packed_rr'; rd: number; rs: number };

/**
 * Symbol table entry
 */
export interface Symbol {
  name: string;
  address: number;
  type: 'code' | 'data' | 'const';
  size?: number;
}

/**
 * Assembler options
 */
export interface AssemblerOptions {
  origin?: number;          // Program origin address
  entryPoint?: string;      // Entry label name
  debug?: boolean;          // Include debug info
  optimize?: boolean;       // Enable peephole optimizations
  firmware?: boolean;       // Prepend firmware ROM (default: true)
}

/**
 * Assembler result
 */
export interface AssemblerResult {
  program: ProgramImage;
  symbols: Symbol[];
  diagnostics: Diagnostic[];
}

/**
 * Diagnostic message
 */
export interface Diagnostic {
  severity: 'error' | 'warning' | 'info';
  message: string;
  line: number;
  column: number;
  file: string;
}

/**
 * Program Image (matches Java ProgramImage structure)
 */
export interface ProgramImage {
  formatVersion: number;
  isaVersion: number;
  minAbiVersion: number;
  minFirmwareVersion: number;
  entryPoint: number;
  code: Uint8Array;
  constants: number[];
  capabilities: string[];
  hash: Uint8Array;
  debugInfo?: string;
}
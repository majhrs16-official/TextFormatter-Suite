/**
 * iFlow VM Opcode Definitions
 * Matches the Java ISA specification exactly
 */
export declare enum Opcode {
    LI = 0,// RI16: Load Immediate 16-bit
    MOV = 1,// RR: Move register
    LDB = 2,// MEM: Load Byte
    LDW = 3,// MEM: Load Word
    STB = 4,// MEM: Store Byte
    STW = 5,// MEM: Store Word
    COPY = 6,// BLOCK: Copy non-overlapping
    MOVE = 7,// BLOCK: Move (allows overlap)
    ADD = 32,// RR: Add
    SUB = 33,// RR: Subtract
    MUL = 34,// RR: Multiply
    DIV = 35,// RR: Divide (signed)
    MOD = 36,// RR: Modulo (signed)
    AND = 40,// RR: Bitwise AND
    OR = 41,// RR: Bitwise OR
    XOR = 42,// RR: Bitwise XOR
    NOT = 43,// R: Bitwise NOT
    SHL = 44,// RR: Shift Left Logical
    SHR = 45,// RR: Shift Right Logical
    SAR = 46,// RR: Shift Right Arithmetic
    CMP = 48,// RR: Compare (signed)
    CMPU = 49,// RR: Compare (unsigned)
    JMP = 64,// BR16: Jump relative
    JZ = 65,// BR16: Jump if Zero
    JNZ = 66,// BR16: Jump if Not Zero
    JC = 67,// BR16: Jump if Carry
    JV = 68,// BR16: Jump if Overflow
    CALL = 72,// BR16: Call subroutine
    RET = 73,// R: Return
    HOST = 96,// HOST: Generic host service call
    HALT = 104,// R: Halt execution (normal)
    TRAP = 105
}
/**
 * Instruction sizes in bytes
 */
export declare const INSTRUCTION_SIZES: Record<Opcode, number>;
/**
 * Register indices
 */
export declare enum Register {
    R0 = 0,
    R1 = 1,
    R2 = 2,
    R3 = 3,
    R4 = 4,
    R5 = 5,
    R6 = 6,
    R7 = 7,
    IP = 8,
    SP = 9,
    FP = 10,
    FL = 11
}
/**
 * Service IDs (matching Java HostServiceRegistry)
 */
export declare enum ServiceId {
    PROPERTY_READ = 256,
    PROPERTY_WRITE = 257,
    PROPERTY_EXISTS = 258,
    PERMISSION_CHECK = 512,
    PERMISSION_QUERY = 513,
    ROUTE_DECISION = 768,
    ROUTE_REDIRECT = 769,
    ROUTE_RATE_LIMIT = 770,
    STATE_READ = 1280,
    STATE_WRITE = 1281,
    CHANNEL_RESOLVE = 1536,
    CHANNEL_POLICY = 1537,
    MESSAGE_CLONE = 1792,
    MESSAGE_ID = 1793
}
/**
 * Property scopes
 */
export declare enum Scope {
    MESSAGE = 1,
    EMITTER = 2,
    RECIPIENT = 3,
    CHANNEL = 4,
    SERVER = 5
}
/**
 * Property IDs
 */
export declare enum PropertyId {
    CONTENT = 16,
    CHANNEL = 17,
    DIRECTION = 18,
    TYPE = 19,
    LANG_SOURCE = 20,
    LANG_TARGET = 21,
    COLOR_MODE = 22,
    FORMAT_PAPI = 23,
    SLEEP_MILLIS = 24,
    SOUNDS = 25,
    CANCELLED = 26,
    PROCESSED = 27,
    TRANSLATE = 28
}
/**
 * Routing decision codes
 */
export declare enum Decision {
    LOG = 0,
    DROP = 1,
    REJECT = 2,
    REDIRECT = 3,
    CHANNEL_REDIRECT = 4,
    RATE_LIMIT = 5,
    CONTINUE = 6
}
/**
 * Register names for parser
 */
export declare const REGISTER_NAMES: Record<string, number>;
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
export type Operand = {
    type: 'reg';
    value: number;
} | {
    type: 'imm8';
    value: number;
} | {
    type: 'imm16';
    value: number;
} | {
    type: 'vaddr';
    value: number;
} | {
    type: 'label';
    name: string;
} | {
    type: 'packed_rr';
    rd: number;
    rs: number;
};
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
    origin?: number;
    entryPoint?: string;
    debug?: boolean;
    optimize?: boolean;
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
//# sourceMappingURL=types.d.ts.map
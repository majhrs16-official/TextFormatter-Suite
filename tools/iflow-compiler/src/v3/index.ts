/** Public surface of the iFlow v3 assembler. */

export { AssemblerV3 } from './assembler';
export { classifyInstruction, encodeInstruction, instructionLength } from './encoder';
export { parseSource, registerNumber } from './parser';
export * from './types';

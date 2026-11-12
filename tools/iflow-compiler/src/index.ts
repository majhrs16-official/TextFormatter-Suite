/**
 * Public API of the iFlow assembler.
 *
 * The CLI (`cli.ts`, installed as `iflow-as`) is the primary entry point; this
 * barrel exposes the programmatic surface so the package can also be consumed
 * as a library (`package.json` points `main`/`types` here).
 */
export { Assembler } from './assembler';
export {
  DisassemblerError,
  disassemble,
  decodeProgramImage,
  formatListing,
  parseProgramImage,
} from './disassembler';
export type {
  BasicBlock,
  DisassembledInstruction,
  DisassemblyDiagnostic,
  DisassemblyOptions,
  DisassemblyResult,
  FlowKind,
} from './disassembler';
export * from './types';

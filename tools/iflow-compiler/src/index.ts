/**
 * Public API of the iFlow assembler.
 *
 * The CLI (`cli.ts`, installed as `iflow-as`) is the primary entry point; this
 * barrel exposes the programmatic surface so the package can also be consumed
 * as a library (`package.json` points `main`/`types` here).
 */
export { Assembler } from './assembler';
export * from './types';

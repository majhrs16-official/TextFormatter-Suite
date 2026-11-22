/** ISA v2 backend for the unified `iflow-as` CLI. */

import { Assembler } from './assembler';
import { printSymbolListing } from './cli-listing';
import * as fs from 'fs';
import * as path from 'path';

export interface V2CliOptions {
  readonly inputFile: string;
  readonly outputFile?: string;
  readonly origin: string;
  readonly entryPoint?: string;
  readonly debug?: boolean;
  readonly list?: boolean;
  readonly firmware: boolean;
  readonly verbose?: boolean;
}

export async function runV2Cli(options: V2CliOptions): Promise<number> {
  const inputPath = path.resolve(options.inputFile);
  if (!fs.existsSync(inputPath)) {
    console.error(`Error: Input file not found: ${inputPath}`);
    return 1;
  }

  const outputFile = options.outputFile
    ? path.resolve(options.outputFile)
    : path.join(path.dirname(inputPath), path.basename(inputPath, path.extname(inputPath)) + '.flow');

  try {
    const source = fs.readFileSync(inputPath, 'utf-8');
    const asm = new Assembler({
      origin: parseInt(options.origin, 0),
      entryPoint: options.entryPoint,
      debug: options.debug,
      firmware: options.firmware,
    });

    const result = await asm.assemble(source, inputPath);
    const { program, symbols, diagnostics } = result;

    for (const diag of diagnostics) {
      const prefix = diag.severity === 'error' ? 'Error'
        : diag.severity === 'warning' ? 'Warning' : 'Info';
      console.error(`${prefix} [${diag.file}:${diag.line}:${diag.column}]: ${diag.message}`);
    }

    const errors = diagnostics.filter(d => d.severity === 'error');
    if (errors.length > 0) {
      console.error(`\nAssembly failed with ${errors.length} error(s)`);
      return 1;
    }

    const programBytes = asm.serializeProgramImage(program);
    fs.writeFileSync(outputFile, Buffer.from(programBytes));
    console.log(`✓ Assembled ${options.inputFile} → ${outputFile} (${programBytes.length} bytes)`);

    if (options.list) {
      printSymbolListing(symbols);
    }
    return 0;
  } catch (error: any) {
    console.error(`Error: ${error.message}`);
    if (options.verbose && error.stack) {
      console.error(error.stack);
    }
    return 1;
  }
}

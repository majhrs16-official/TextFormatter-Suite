/** ISA v2 backend for the unified `iflow-as` CLI. */

import { Assembler } from './assembler';
import type { Symbol } from './types';
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
      const listFile = path.join(
        path.dirname(outputFile),
        path.basename(outputFile, path.extname(outputFile)) + '.lst');
      generateListing(inputPath, source, symbols, listFile);
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

function generateListing(
  inputPath: string,
  source: string,
  symbols: Symbol[],
  listFile: string,
): void {
  const lines = source.split('\n');
  let output = '';

  output += '; iFlow Assembly Listing\n';
  output += `; Source: ${inputPath}\n`;
  output += `; Generated: ${new Date().toISOString()}\n\n`;

  output += '; Symbol Table:\n';
  for (const sym of symbols) {
    output += `; ${sym.name.padEnd(32)} = 0x${sym.address.toString(16).padStart(4, '0')} (${sym.type})\n`;
  }
  output += '\n';

  output += '; Source:\n';
  for (let i = 0; i < lines.length; i++) {
    output += `${String(i + 1).padStart(4, ' ')}: ${lines[i]}\n`;
  }

  fs.writeFileSync(listFile, output);
  console.log(`✓ Listing written to ${listFile}`);
}

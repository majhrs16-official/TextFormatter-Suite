/** ISA v3 backend for the unified `iflow-as` CLI. */

import { AssemblerV3, AssemblyError } from './v3';
import * as fs from 'fs';
import * as path from 'path';

export interface V3CliOptions {
  readonly inputFile: string;
  readonly outputFile?: string;
  readonly entryPoint?: string;
  readonly reset?: string;
  readonly maxRom?: string;
  readonly list?: boolean;
  readonly verbose?: boolean;
}

export function runV3Cli(options: V3CliOptions): number {
  const inputPath = path.resolve(options.inputFile);
  if (!fs.existsSync(inputPath)) {
    console.error(`Error: Input file not found: ${inputPath}`);
    return 1;
  }

  const resetVector = parseNumberOption(
    options.reset, '-R/--reset', 0, 0xFFFF_FFFF, undefined);
  if (resetVector.error) {
    console.error(resetVector.error);
    return 2;
  }
  const maxRomSize = parseNumberOption(
    options.maxRom, '--max-rom', 1, 16 * 1024 * 1024, 16 * 1024 * 1024);
  if (maxRomSize.error) {
    console.error(maxRomSize.error);
    return 2;
  }

  const outputFile = options.outputFile
    ? path.resolve(options.outputFile)
    : path.join(path.dirname(inputPath), path.basename(inputPath, path.extname(inputPath)) + '.rom');

  try {
    const source = fs.readFileSync(inputPath, 'utf-8');
    const assembler = new AssemblerV3();
    const result = assembler.assemble(source, inputPath, {
      resetVector: resetVector.value,
      entryPoint: options.entryPoint,
      maxRomSize: maxRomSize.value,
    });

    for (const diagnostic of result.diagnostics) {
      const prefix = diagnostic.severity === 'error'
        ? 'Error'
        : diagnostic.severity === 'warning' ? 'Warning' : 'Info';
      console.error(
        `${prefix} [${diagnostic.file}:${diagnostic.line}:${diagnostic.column}]: ${diagnostic.message}`);
    }

    fs.writeFileSync(outputFile, Buffer.from(result.bytes));
    console.log(`✓ Assembled ${options.inputFile} → ${outputFile} (${result.bytes.length} bytes)`);

    if (options.list) {
      for (const symbol of result.symbols) {
        console.log(`  ${symbol.name.padEnd(32)} = 0x${symbol.address.toString(16).padStart(8, '0')}`);
      }
    }
    return 0;
  } catch (error) {
    if (error instanceof AssemblyError) {
      for (const diagnostic of error.diagnostics) {
        console.error(
          `Error [${diagnostic.file}:${diagnostic.line}:${diagnostic.column}]: ${diagnostic.message}`);
      }
    } else {
      console.error(`Error: ${(error as Error).message}`);
      if (options.verbose && (error as Error).stack) {
        console.error((error as Error).stack);
      }
    }
    return 1;
  }
}

function parseNumberOption(
  text: string | undefined,
  name: string,
  minimum: number,
  maximum: number,
  fallback: number | undefined,
): { value?: number; error?: string } {
  if (text === undefined) {
    return { value: fallback };
  }
  const value = Number(text);
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    return { error: `${name} must be an integer between ${minimum} and ${maximum}` };
  }
  return { value };
}

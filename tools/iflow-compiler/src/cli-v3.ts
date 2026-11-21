#!/usr/bin/env node
/** iFlow v3 ROM assembler CLI (`iflow-as-v3`). */

import { AssemblerV3, AssemblyError } from './v3';
import * as fs from 'fs';
import * as path from 'path';
import { program } from 'commander';

program
  .name('iflow-as-v3')
  .description('iFlow v3 assembler - compiles .flowasm to a FLOWROM v3 image')
  .version('1.0.0')
  .argument('<input>', 'input assembly file (.flowasm)')
  .option('-o, --output <file>', 'output ROM file (.flow)')
  .option('-e, --entry <label>', 'reset vector label')
  .option('-R, --reset <address>', 'explicit reset vector (hex)')
  .option('--max-rom <bytes>', 'maximum ROM size in bytes', '16777216')
  .option('-l, --list', 'print the symbol table')
  .option('-v, --verbose', 'verbose diagnostics');

program.parse(process.argv);

const options = program.opts();
const inputFile = program.args[0];
if (!inputFile) {
  console.error('Error: No input file specified');
  program.help();
  process.exit(1);
}

const inputPath = path.resolve(inputFile);
if (!fs.existsSync(inputPath)) {
  console.error(`Error: Input file not found: ${inputPath}`);
  process.exit(1);
}

const outputFile = options.output
  ? path.resolve(options.output)
  : path.join(path.dirname(inputPath), path.basename(inputPath, path.extname(inputPath)) + '.flow');

try {
  const source = fs.readFileSync(inputPath, 'utf-8');
  const assembler = new AssemblerV3();
  const result = assembler.assemble(source, inputPath, {
    resetVector: options.reset === undefined ? undefined : Number.parseInt(options.reset, 0),
    entryPoint: options.entry,
    maxRomSize: Number.parseInt(options.maxRom, 0),
  });

  for (const diagnostic of result.diagnostics) {
    const prefix = diagnostic.severity === 'error'
      ? 'Error'
      : diagnostic.severity === 'warning' ? 'Warning' : 'Info';
    console.error(
      `${prefix} [${diagnostic.file}:${diagnostic.line}:${diagnostic.column}]: ${diagnostic.message}`);
  }

  fs.writeFileSync(outputFile, Buffer.from(result.bytes));
  console.log(`✓ Assembled ${inputFile} → ${outputFile} (${result.bytes.length} bytes)`);

  if (options.list) {
    for (const symbol of result.symbols) {
      console.log(`  ${symbol.name.padEnd(32)} = 0x${symbol.address.toString(16).padStart(8, '0')}`);
    }
  }
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
  process.exit(1);
}

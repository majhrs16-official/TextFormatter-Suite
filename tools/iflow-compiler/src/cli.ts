#!/usr/bin/env node
/**
 * iFlow VM Assembler CLI
 */
import { Assembler } from './assembler';
import * as fs from 'fs';
import * as path from 'path';
import { program } from 'commander';

program
  .name('iflow-as')
  .description('iFlow VM Assembler - compiles .flowasm to .flow binary')
  .version('1.0.0')
  .argument('<input>', 'input assembly file (.flowasm)')
  .option('-o, --output <file>', 'output binary file (.flow)')
  .option('-O, --origin <address>', 'program origin address (hex)', '0x0000')
  .option('-e, --entry <label>', 'entry point label')
  .option('-d, --debug', 'include debug info')
  .option('-l, --list', 'output assembly listing')
  .option('-v, --verbose', 'verbose output');

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
  
  const assembler = require('./assembler').Assembler;
  const asm = new Assembler({
    origin: parseInt(options.origin, 0),
    entryPoint: options.entry,
    debug: options.debug,
  });

  const { program, symbols, diagnostics } = asm.assemble(source, inputPath);

  // Output diagnostics
  for (const diag of diagnostics) {
    const prefix = diag.severity === 'error' ? 'Error' : diag.severity === 'warning' ? 'Warning' : 'Info';
    console.error(`${prefix} [${diag.file}:${diag.line}:${diag.column}]: ${diag.message}`);
  }

  const errors = diagnostics.filter(d => d.severity === 'error');
  if (errors.length > 0) {
    console.error(`\nAssembly failed with ${errors.length} error(s)`);
    process.exit(1);
  }

  // Write output
  const programBytes = asm.serializeProgramImage(program);
  fs.writeFileSync(outputFile, Buffer.from(programBytes));
  console.log(`✓ Assembled ${inputFile} → ${outputFile} (${programBytes.length} bytes)`);

  if (options.list) {
    const listFile = outputFile.replace('.flow', '.lst');
    const symbolMap = new Map(symbols.map(s => [s.name, s.address]));
    generateListing(source, symbolMap, outputFile.replace('.flow', '.lst'));
  }

} catch (error: any) {
  console.error(`Error: ${error.message}`);
  if (options.verbose && error.stack) {
    console.error(error.stack);
  }
  process.exit(1);
}

function generateListing(source: string, symbols: Map<string, number>, outputFile: string) {
  // TODO: Generate assembly listing
  console.log(`Listing would be written to ${outputFile}`);
}
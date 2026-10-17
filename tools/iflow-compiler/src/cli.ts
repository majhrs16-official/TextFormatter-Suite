#!/usr/bin/env node
/**
 * iFlow VM Assembler CLI
 */
import { Assembler } from './assembler';
import type { Symbol } from './types';
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
  .option('--no-firmware', 'do not prepend the firmware ROM (bare-metal program)')
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

async function main() {
  try {
    const source = fs.readFileSync(inputPath, 'utf-8');

    const asm = new Assembler({
      origin: parseInt(options.origin, 0),
      entryPoint: options.entry,
      debug: options.debug,
      firmware: options.firmware,
    });

    const result = await asm.assemble(source, inputPath);
    const { program, symbols, diagnostics } = result;

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
      generateListing(source, symbols, listFile, asm);
    }

  } catch (error: any) {
    console.error(`Error: ${error.message}`);
    if (options.verbose && error.stack) {
      console.error(error.stack);
    }
    process.exit(1);
  }
}

main();

function generateListing(source: string, symbols: Symbol[], listFile: string, asm: Assembler) {
  const lines = source.split('\n');
  let output = '';
  
  // Header
  output += '; iFlow Assembly Listing\n';
  output += `; Source: ${inputPath}\n`;
  output += `; Generated: ${new Date().toISOString()}\n\n`;
  
  // Symbol table
  output += '; Symbol Table:\n';
  for (const sym of symbols) {
    output += `; ${sym.name.padEnd(32)} = 0x${sym.address.toString(16).padStart(4, '0')} (${sym.type})\n`;
  }
  output += '\n';
  
  // Source with addresses (simplified - just show source with line numbers)
  output += '; Source:\n';
  for (let i = 0; i < lines.length; i++) {
    output += `${String(i + 1).padStart(4, ' ')}: ${lines[i]}\n`;
  }
  
  fs.writeFileSync(listFile, output);
  console.log(`✓ Listing written to ${listFile}`);
}
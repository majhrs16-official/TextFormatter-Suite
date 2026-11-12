#!/usr/bin/env node
/**
 * iFlow disassembler CLI.
 *
 * Decodes the user range of a `.flow` v2 image and prints a listing or JSON.
 * Kept separate from `iflow-as` so the assembler CLI keeps its positional
 * contract.
 */
import { disassemble, formatListing } from './disassembler';
import * as fs from 'fs';
import * as path from 'path';
import { program } from 'commander';

program
  .name('iflow-dis')
  .description('iFlow VM disassembler - decodes the user range of a .flow binary')
  .version('1.0.0')
  .argument('<input>', 'input binary file (.flow)')
  .option('--json', 'print JSON instead of a listing')
  .option('--user-start <address>', 'override the user code start (hex)')
  .option('--no-verify', 'skip the SHA-256 integrity check');

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

async function main(): Promise<void> {
  try {
    const bytes = new Uint8Array(fs.readFileSync(inputPath));
    const userStart =
      options.userStart === undefined ? undefined : parseInt(String(options.userStart), 0);
    if (userStart !== undefined && Number.isNaN(userStart)) {
      throw new Error('invalid --user-start value: ' + options.userStart);
    }
    const result = await disassemble(bytes, {
      userStart,
      verifyHash: options.verify,
    });
    if (options.json) {
      console.log(JSON.stringify(result, null, 2));
    } else {
      process.stdout.write(formatListing(result));
    }
    if (result.diagnostics.length > 0) {
      process.exitCode = 1;
    }
  } catch (error: any) {
    console.error(`Error: ${error.message}`);
    process.exit(1);
  }
}

main();

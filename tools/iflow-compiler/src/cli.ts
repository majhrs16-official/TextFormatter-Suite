#!/usr/bin/env node
/**
 * Unified iFlow assembler CLI.
 *
 * The argument surface is shared; `-f/--format` selects the ISA backend.
 * Each backend keeps its own assembler module and rejects options that do not
 * apply to it, mirroring NASM's `-f <format>` model without merging opcodes.
 */
import { program } from 'commander';
import { runV2Cli } from './cli-v2';
import { runV3Cli } from './cli-v3';

const FORMAT_IFLOW_16 = 'iflow-16';
const FORMAT_IFLOW_32 = 'iflow-32';

program
  .name('iflow-as')
  .description('iFlow assembler - .flowasm for iflow-16 or iflow-32')
  .version('1.0.0')
  .argument('<input>', 'input assembly file (.flowasm)')
  .option('-f, --format <format>',
    `output format: ${FORMAT_IFLOW_16} or ${FORMAT_IFLOW_32}`, FORMAT_IFLOW_16)
  .option('-o, --output <file>', 'output binary file (.flow)')
  .option('-e, --entry <label>', 'entry point or reset vector label')
  .option('-l, --list', 'emit the symbol listing')
  .option('-v, --verbose', 'verbose diagnostics')
  .option('-O, --origin <address>', `program origin (hex, ${FORMAT_IFLOW_16} only)`)
  .option('-d, --debug', `include debug info (${FORMAT_IFLOW_16} only)`)
  .option('--no-firmware', `bare-metal program without firmware ROM (${FORMAT_IFLOW_16} only)`)
  .option('-R, --reset <address>', `explicit reset vector (hex, ${FORMAT_IFLOW_32} only)`)
  .option('--max-rom <bytes>', `maximum ROM size (${FORMAT_IFLOW_32} only)`);

program.parse(process.argv);

main()
  .then(code => {
    process.exitCode = code;
  })
  .catch(error => {
    console.error(`Error: ${(error as Error).message}`);
    process.exitCode = 1;
  });

async function main(): Promise<number> {
  const inputFile = program.args[0];
  if (!inputFile) {
    console.error('Error: No input file specified');
    return 1;
  }

  const options = program.opts();
  const explicit = (name: string) => program.getOptionValueSource(name) === 'cli';

  if (options.format === FORMAT_IFLOW_16) {
    const incompatible: string[] = [];
    if (explicit('reset')) {
      incompatible.push('-R/--reset');
    }
    if (explicit('maxRom')) {
      incompatible.push('--max-rom');
    }
    if (incompatible.length > 0) {
      return reject(FORMAT_IFLOW_16, incompatible);
    }
    return runV2Cli({
      inputFile,
      outputFile: options.output,
      origin: options.origin ?? '0x0000',
      entryPoint: options.entry,
      debug: options.debug,
      list: options.list,
      firmware: options.firmware,
      verbose: options.verbose,
    });
  }

  if (options.format === FORMAT_IFLOW_32) {
    const incompatible: string[] = [];
    if (explicit('origin')) {
      incompatible.push('-O/--origin');
    }
    if (explicit('debug')) {
      incompatible.push('-d/--debug');
    }
    if (explicit('firmware')) {
      incompatible.push('--no-firmware');
    }
    if (incompatible.length > 0) {
      return reject(FORMAT_IFLOW_32, incompatible);
    }
    return runV3Cli({
      inputFile,
      outputFile: options.output,
      entryPoint: options.entry,
      reset: options.reset,
      maxRom: options.maxRom,
      list: options.list,
      verbose: options.verbose,
    });
  }

  console.error(
    `Error: unknown format '${options.format}'. Use ${FORMAT_IFLOW_16} or ${FORMAT_IFLOW_32}.`);
  return 2;
}

function reject(format: string, options: string[]): number {
  console.error(
    `Error: ${format} does not accept ${options.join(', ')}. Choose the matching --format.`);
  return 2;
}

#!/usr/bin/env node
"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
/**
 * iFlow VM Assembler CLI
 */
const assembler_1 = require("./assembler");
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const commander_1 = require("commander");
commander_1.program
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
commander_1.program.parse(process.argv);
const options = commander_1.program.opts();
const inputFile = commander_1.program.args[0];
if (!inputFile) {
    console.error('Error: No input file specified');
    commander_1.program.help();
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
        const asm = new assembler_1.Assembler({
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
    }
    catch (error) {
        console.error(`Error: ${error.message}`);
        if (options.verbose && error.stack) {
            console.error(error.stack);
        }
        process.exit(1);
    }
}
main();
function generateListing(source, symbols, listFile, asm) {
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
//# sourceMappingURL=cli.js.map
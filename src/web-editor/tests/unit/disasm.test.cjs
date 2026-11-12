#!/usr/bin/env node
/* tests/unit/disasm.test.cjs — unit tests for Suite.disasm */

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const dir = path.join(__dirname, '..', '..');
const repo = path.join(dir, '..', '..');
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
  runScripts: 'outside-only',
  url: 'https://localhost/',
});
const { window } = dom;
window.eval(fs.readFileSync(path.join(dir, 'js', 'disasm.js'), 'utf8'));
const Disasm = window.Suite.disasm;

let passed = 0;
let failed = 0;

function assert(condition, message) {
  if (condition) {
    console.log('✓ ' + message);
    passed++;
  } else {
    console.log('✗ ' + message);
    failed++;
  }
}

function assertEq(actual, expected, message) {
  const same = JSON.stringify(actual) === JSON.stringify(expected);
  assert(same, message + (same ? '' : ' — expected ' + JSON.stringify(expected) + ' got ' + JSON.stringify(actual)));
}

function fixture(name) {
  return new Uint8Array(fs.readFileSync(path.join(repo, 'tools', 'iflow-compiler', 'test', name + '.flow')));
}

function tsOpcodeTable() {
  const source = fs.readFileSync(path.join(repo, 'tools', 'iflow-compiler', 'src', 'types.ts'), 'utf8');
  const start = source.indexOf('export enum Opcode {');
  const body = source.slice(start, source.indexOf('}', start));
  const table = {};
  for (const match of body.matchAll(/([A-Z]+)\s*=\s*(0x[0-9a-fA-F]+|\d+)/g)) {
    table[match[1]] = Number(match[2]);
  }
  return table;
}

function tsSizeTable() {
  const source = fs.readFileSync(path.join(repo, 'tools', 'iflow-compiler', 'src', 'types.ts'), 'utf8');
  const start = source.indexOf('export const INSTRUCTION_SIZES');
  const body = source.slice(start, source.indexOf('};', start));
  const table = {};
  for (const match of body.matchAll(/\[Opcode\.([A-Z]+)\]:\s*(\d+)/g)) {
    table[match[1]] = Number(match[2]);
  }
  return table;
}

function javaOpcodeTable() {
  const file = path.join(
    repo,
    'src',
    'iflow-vm',
    'src',
    'main',
    'java',
    'me',
    'majhrs16',
    'suite',
    'iflow',
    'vm',
    'isa',
    'Opcode.java'
  );
  const source = fs.readFileSync(file, 'utf8');
  const table = {};
  for (const match of source.matchAll(/public static final int ([A-Z]+)\s*=\s*(0x[0-9a-fA-F]+)/g)) {
    table[match[1]] = Number(match[2]);
  }
  return table;
}

console.log('\n=== Suite.disasm Unit Tests ===\n');

console.log('--- ISA table parity ---');
const tsOpcodes = tsOpcodeTable();
const tsSizes = tsSizeTable();
const javaOpcodes = javaOpcodeTable();
const jsOpcodes = {};
for (const [opcode, name] of Object.entries(Disasm.OPCODES)) {
  jsOpcodes[name] = Number(opcode);
}
assert(Object.keys(tsOpcodes).length >= 34, 'parses the TypeScript opcode enum');
assertEq(jsOpcodes, tsOpcodes, 'the JS opcode table matches types.ts');
assertEq(jsOpcodes, javaOpcodes, 'the JS opcode table matches Opcode.java');
const jsSizes = {};
for (const [opcode, name] of Object.entries(Disasm.OPCODES)) {
  jsSizes[name] = Disasm.SIZES[opcode];
}
assertEq(jsSizes, tsSizes, 'the JS size table matches types.ts');
for (const [name, size] of Object.entries(tsSizes)) {
  assert(
    Disasm.OPCODES[tsOpcodes[name]] === name && Disasm.SIZES[tsOpcodes[name]] === size,
    'consistent entry for ' + name
  );
}

console.log('\n--- simple.flow ---');
const simple = Disasm.decode(fixture('simple'));
assertEq(simple.userStart, 0x1000, 'starts at the user origin');
assertEq(simple.userEnd, 0x1005, 'ends at the code length');
assertEq(simple.entryPoint, 0x0202, 'reports the BIOS entry point');
assertEq(simple.instructions.length, 2, 'decodes two instructions');
assertEq(simple.instructions[0].text, 'LI R0, 0x1234', 'decodes LI');
assertEq(simple.instructions[0].bytes, [0x00, 0x00, 0x12, 0x34], 'keeps the instruction bytes');
assertEq(simple.instructions[1].text, 'HALT', 'decodes HALT');
assertEq(simple.blocks.length, 1, 'builds one block');
assertEq(simple.blocks[0].successors, [], 'the single block has no successors');
assertEq(simple.blocks[0].exits, [], 'the single block has no exits');
assertEq(simple.diagnostics, [], 'no diagnostics');

console.log('\n--- main_only.flow ---');
const mainOnly = Disasm.decode(fixture('main_only'));
assertEq(mainOnly.diagnostics, [], 'no diagnostics');
const call = mainOnly.instructions.find(instruction => instruction.mnemonic === 'CALL');
assert(call && call.target === 0x1004, 'resolves the CALL target');
const callBlock = mainOnly.blocks.find(block => block.start === 0x1000);
assert(
  callBlock && callBlock.successors.includes(0x1004) && callBlock.successors.includes(0x1003),
  'a CALL keeps the callee and the return edges'
);
const ret = mainOnly.instructions.find(instruction => instruction.mnemonic === 'RET');
assert(ret && ret.flow === 'return', 'marks RET as a return');
assertEq(mainOnly.blocks[mainOnly.blocks.length - 1].successors, [], 'the last block ends the CFG');

console.log('\n--- firmware.flow ---');
const firmware = Disasm.decode(fixture('firmware'));
assertEq(firmware.diagnostics, [], 'no diagnostics');
const exits = firmware.blocks.flatMap(block => block.exits);
assert(exits.includes(0x0102), 'a CALL into firmware is an exit');
assert(
  firmware.instructions.some(instruction => instruction.mnemonic === 'HOST'),
  'decodes HOST'
);

console.log('\n--- irq.flow y bios_screen.flow ---');
for (const name of ['irq', 'bios_screen']) {
  const result = Disasm.decode(fixture(name));
  assertEq(result.diagnostics, [], name + ' has no diagnostics');
  assert(result.instructions.length > 0, name + ' decodes instructions');
  const last = result.instructions[result.instructions.length - 1];
  assert(last.address + last.size === result.userEnd, name + ' covers the user range');
}

console.log('\n--- rango de usuario ---');
const override = Disasm.decode(fixture('simple'), { userStart: 0x1004 });
assertEq(override.instructions.length, 1, 'honours userStart');
assertEq(override.instructions[0].text, 'HALT', 'the override lands on the HALT');
let threw = false;
try {
  Disasm.decode(fixture('simple'), { userStart: 0x2000 });
} catch (error) {
  threw = error.name === 'DisasmError';
}
assert(threw, 'rejects a userStart outside the code');

console.log('\n--- errores y resincronización ---');
const unknown = fixture('simple');
unknown[20 + 0x1000] = 0xf0;
const unknownResult = Disasm.decode(unknown);
assert(
  unknownResult.diagnostics.some(diagnostic => diagnostic.message.includes('opcode desconocido 0xF0')),
  'reports the unknown opcode'
);
assertEq(unknownResult.instructions[0].mnemonic, '.byte', 'emits .byte');
assert(unknownResult.instructions.length > 1, 'resynchronises after the .byte');

const badMagic = fixture('simple');
badMagic[0] = 0xff;
threw = false;
try {
  Disasm.decode(badMagic);
} catch (error) {
  threw = error.name === 'DisasmError' && /magic/.test(error.message);
}
assert(threw, 'rejects a bad magic');

threw = false;
try {
  Disasm.decode(new Uint8Array([0, 1, 2]));
} catch (error) {
  threw = error.name === 'DisasmError';
}
assert(threw, 'rejects a short header');

const truncated = fixture('simple');
truncated[10] = 0xff;
truncated[11] = 0xff;
threw = false;
try {
  Disasm.decode(truncated);
} catch (error) {
  threw = error.name === 'DisasmError' && /truncada/.test(error.message);
}
assert(threw, 'rejects a truncated code section');

console.log('\n--- listado ---');
const listing = Disasm.formatListing(simple);
assert(listing.includes('; entry 0x0202'), 'the listing reports the entry point');
assert(listing.includes('0x1000  00 00 12 34'), 'the listing shows address and bytes');
assert(listing.includes('LI R0, 0x1234'), 'the listing shows the mnemonic');
assert(listing.includes('; CFG'), 'the listing shows the CFG');
const unknownListing = Disasm.formatListing(unknownResult);
assert(unknownListing.includes('; diagnósticos'), 'the listing shows the diagnostics');

console.log('\n' + passed + ' passed, ' + failed + ' failed\n');
if (failed > 0) {
  process.exitCode = 1;
}

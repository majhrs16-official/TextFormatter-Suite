#!/usr/bin/env node
'use strict';

// Unified CLI tests: iflow-16 default and iflow-32 format selection and explicit
// rejection of options that belong to the other format.

const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const root = path.resolve(__dirname, '..', '..');
const cli = path.join(root, 'dist', 'cli.js');
const iflow16Fixture = path.join(root, 'test', 'main_only.flowasm');
const iflow32Fixture = path.resolve(
  root, '..', '..', 'src', 'iflow-32', 'src', 'test', 'resources', 'rom', 'v3',
  'assembler-minimal.flowasm');

if (!fs.existsSync(cli)) {
  console.error(`Missing ${cli}\nRun "npm run build" before "npm run test:cli".`);
  process.exit(2);
}

const outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'iflow-cli-'));
let passed = 0;
let failed = 0;

function run(args) {
  return spawnSync(process.execPath, [cli, ...args], { encoding: 'utf8' });
}

function test(name, body) {
  try {
    body();
    passed++;
    console.log(`PASS ${name}`);
  } catch (error) {
    failed++;
    console.log(`FAIL ${name}`);
    console.log(`  ${error.message}`);
  }
}

test('defaults to iflow-16', () => {
  const output = path.join(outDir, 'iflow16.flow');
  const result = run([iflow16Fixture, '--no-firmware', '-o', output]);
  assert.strictEqual(result.status, 0, result.stderr);
  const bytes = fs.readFileSync(output);
  assert.strictEqual(bytes.subarray(0, 4).toString('ascii'), 'FLOW');
  assert.strictEqual(bytes[4], 2);

  const explicitOutput = path.join(outDir, 'iflow16-explicit.flow');
  const explicit = run([iflow16Fixture, '-f', 'iflow-16', '--no-firmware', '-o', explicitOutput]);
  assert.strictEqual(explicit.status, 0, explicit.stderr);
});

test('writes the iflow-16 listing next to the output', () => {
  const output = path.join(outDir, 'listed.bin');
  const result = run([iflow16Fixture, '--no-firmware', '-l', '-o', output]);
  assert.strictEqual(result.status, 0, result.stderr);
  assert.ok(fs.existsSync(path.join(outDir, 'listed.lst')), 'listing file is missing');
  assert.strictEqual(fs.readFileSync(output).subarray(0, 4).toString('ascii'), 'FLOW');
});

test('selects iflow-32 with -f iflow-32', () => {
  const output = path.join(outDir, 'iflow32.flow');
  const result = run([iflow32Fixture, '-f', 'iflow-32', '-o', output, '-l']);
  assert.strictEqual(result.status, 0, result.stderr);
  const bytes = fs.readFileSync(output);
  assert.strictEqual(bytes.subarray(0, 8).toString('ascii'), 'FLOWROM\0');
  assert.strictEqual(bytes.readUInt16LE(8), 3);
  assert.ok(result.stdout.includes('start'), 'symbol table must be printed');
});

test('iflow-32 default output uses the .rom extension', () => {
  const input = path.join(outDir, 'kernel.flowasm');
  fs.copyFileSync(iflow32Fixture, input);
  const result = run([input, '-f', 'iflow-32']);
  assert.strictEqual(result.status, 0, result.stderr);
  const output = path.join(outDir, 'kernel.rom');
  assert.ok(fs.existsSync(output), 'default output must be kernel.rom');
  assert.strictEqual(fs.readFileSync(output).subarray(0, 8).toString('ascii'), 'FLOWROM\0');
});

test('rejects iflow-32-only options on iflow-16', () => {
  const result = run([iflow16Fixture, '-R', '0x0', '-o', path.join(outDir, 'bad.flow')]);
  assert.strictEqual(result.status, 2);
  assert.ok(result.stderr.includes('iflow-16 does not accept -R/--reset'), result.stderr);
});

test('rejects iflow-16-only options on iflow-32', () => {
  const result = run([
    iflow32Fixture, '-f', 'iflow-32', '-O', '0x1000', '-o', path.join(outDir, 'bad3.flow'),
  ]);
  assert.strictEqual(result.status, 2);
  assert.ok(result.stderr.includes('iflow-32 does not accept -O/--origin'), result.stderr);

  const bare = run([
    iflow32Fixture, '-f', 'iflow-32', '--no-firmware', '-o', path.join(outDir, 'bad3b.flow'),
  ]);
  assert.strictEqual(bare.status, 2);
  assert.ok(bare.stderr.includes('iflow-32 does not accept --no-firmware'), bare.stderr);
});

test('rejects unknown formats and out-of-range iflow-32 numbers', () => {
  const unknown = run([iflow32Fixture, '-f', 'nope', '-o', path.join(outDir, 'bad4.flow')]);
  assert.strictEqual(unknown.status, 2);
  assert.ok(unknown.stderr.includes("unknown format 'nope'"), unknown.stderr);

  const reset = run([
    iflow32Fixture, '-f', 'iflow-32', '-R', '0x100000000', '-o', path.join(outDir, 'bad5.flow'),
  ]);
  assert.strictEqual(reset.status, 2);
  assert.ok(reset.stderr.includes('between 0 and 4294967295'), reset.stderr);

  const maxRom = run([
    iflow32Fixture, '-f', 'iflow-32', '--max-rom', '20000000', '-o', path.join(outDir, 'bad6.flow'),
  ]);
  assert.strictEqual(maxRom.status, 2);
  assert.ok(maxRom.stderr.includes('between 1 and 16777216'), maxRom.stderr);
});

fs.rmSync(outDir, { recursive: true, force: true });
console.log(`\n${passed} passed, ${failed} failed`);
process.exitCode = failed === 0 ? 0 : 1;

#!/usr/bin/env node
'use strict';

// Disassembles the committed .flow fixtures and asserts the decoded listing,
// the user-range cut, the CFG and the strict error paths.
//
// Requires the compiled CLI at ../../dist/disasm-cli.js — run "npm run build" first.

const { spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const dir = __dirname;
const compilerDir = path.resolve(dir, '..', '..');
const dis = path.join(compilerDir, 'dist', 'disasm-cli.js');
const asm = path.join(compilerDir, 'dist', 'cli.js');
const fixtures = path.join(compilerDir, 'test');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'iflow-disasm-'));

if (!fs.existsSync(dis)) {
  console.error(`Missing ${dis}\nRun "npm run build" before "npm run test:disasm".`);
  process.exit(2);
}

let passed = 0;
let failed = 0;

function ok(condition, message) {
  if (condition) {
    console.log('  ✔ ' + message);
    passed++;
  } else {
    console.log('  ✘ ' + message);
    failed++;
  }
}

function run(args) {
  return spawnSync(process.execPath, [dis, ...args], { encoding: 'utf8' });
}

function disassemble(file, extra) {
  const res = run([file, '--json', ...(extra || [])]);
  let json = null;
  try {
    json = JSON.parse(res.stdout);
  } catch (error) {
    json = null;
  }
  return { res, json };
}

function sha256(bytes) {
  return crypto.createHash('sha256').update(bytes).digest();
}

function withHash(bytes) {
  const body = bytes.subarray(0, bytes.length - 32);
  return Buffer.concat([body, sha256(body)]);
}

function segmentTocOffset(bytes, wantedType) {
  const tocOffset = bytes.readUInt16LE(10);
  const tocCount = bytes.readUInt16LE(12);
  for (let i = 0; i < tocCount; i++) {
    const base = tocOffset + i * 16;
    if (bytes[base] === wantedType) {
      return base;
    }
  }
  throw new Error(`segment type ${wantedType} not found`);
}

function segmentFileOffset(bytes, wantedType) {
  return bytes.readUInt32LE(segmentTocOffset(bytes, wantedType) + 4);
}

function checkFixture(name) {
  console.log(`\n--- ${name}.flow ---`);
  const { res, json } = disassemble(path.join(fixtures, name + '.flow'));
  ok(res.status === 0, `${name} decodes with exit 0`);
  ok(json !== null, `${name} prints JSON`);
  if (!json) {
    return null;
  }
  ok(json.diagnostics.length === 0, `${name} has no diagnostics`);
  ok(json.userStart === 0x1000, `${name} starts at the user origin`);
  ok(json.instructions.length > 0, `${name} decodes instructions`);
  const last = json.instructions[json.instructions.length - 1];
  ok(
    last.address + last.size === json.userEnd,
    `${name} accounts for every user byte (${json.userEnd - json.userStart})`
  );
  let contiguous = true;
  for (let i = 1; i < json.instructions.length; i++) {
    const previous = json.instructions[i - 1];
    if (previous.address + previous.size !== json.instructions[i].address) {
      contiguous = false;
    }
  }
  ok(contiguous, `${name} instructions are contiguous`);
  const covered = new Set();
  let duplicated = false;
  for (const block of json.blocks) {
    for (const address of block.instructions) {
      if (covered.has(address)) {
        duplicated = true;
      }
      covered.add(address);
    }
  }
  ok(!duplicated, `${name} blocks do not overlap`);
  ok(covered.size === json.instructions.length, `${name} blocks cover every instruction`);
  const starts = new Set(json.blocks.map(block => block.start));
  let validEdges = true;
  for (const block of json.blocks) {
    for (const successor of block.successors) {
      if (!starts.has(successor)) {
        validEdges = false;
      }
    }
  }
  ok(validEdges, `${name} successors point at blocks`);
  return json;
}

const simple = checkFixture('simple');
if (simple) {
  console.log('\n--- simple.flow details ---');
  ok(simple.instructions.length === 2, 'simple has two instructions');
  ok(simple.instructions[0].text === 'LI R0, 0x1234', 'decodes the LI');
  ok(simple.instructions[1].text === 'HALT', 'decodes the HALT');
  ok(simple.blocks.length === 1, 'simple has one block');
  ok(simple.blocks[0].successors.length === 0 && simple.blocks[0].exits.length === 0, 'simple ends the CFG');
  ok(simple.entryPoint === 0x0202, 'reports the BIOS entry point');
}

const mainOnly = checkFixture('main_only');
if (mainOnly) {
  console.log('\n--- main_only.flow details ---');
  const call = mainOnly.instructions.find(instruction => instruction.mnemonic === 'CALL');
  ok(call && call.target === 0x1004, 'resolves the CALL target');
  const callBlock = mainOnly.blocks.find(block => block.start === 0x1000);
  ok(
    callBlock && callBlock.successors.includes(0x1004) && callBlock.successors.includes(0x1003),
    'a CALL keeps the callee and the return edges'
  );
  const ret = mainOnly.instructions.find(instruction => instruction.mnemonic === 'RET');
  ok(ret && ret.flow === 'return', 'decodes the RET');
}

const firmware = checkFixture('firmware');
if (firmware) {
  console.log('\n--- firmware.flow details ---');
  const external = firmware.blocks.flatMap(block => block.exits);
  ok(external.includes(0x0102), 'a CALL into firmware is an exit, not an error');
}

checkFixture('irq');
checkFixture('bios_screen');

console.log('\n--- user range override ---');
const override = disassemble(path.join(fixtures, 'simple.flow'), ['--user-start', '0x1004']);
ok(override.res.status === 0, 'accepts --user-start');
ok(override.json && override.json.instructions.length === 1, 'decodes only the requested range');
ok(override.json && override.json.instructions[0].text === 'HALT', 'the override lands on the HALT');

console.log('\n--- bare-metal image ---');
const baremetal = path.join(tmp, 'baremetal.flow');
const assembled = spawnSync(
  process.execPath,
  [asm, path.join(fixtures, 'simple.flowasm'), '-o', baremetal, '--no-firmware'],
  { encoding: 'utf8' }
);
ok(assembled.status === 0, 'assembles a bare-metal fixture');
const bare = disassemble(baremetal);
ok(bare.res.status === 0 && bare.json && bare.json.userStart === 0, 'a bare-metal image starts at 0x0000');
ok(bare.json && bare.json.diagnostics.length === 0, 'a bare-metal image has no diagnostics');

console.log('\n--- text listing ---');
const listing = run([path.join(fixtures, 'simple.flow')]);
ok(listing.status === 0, 'prints a listing');
ok(listing.stdout.includes('; entry 0x0202'), 'the listing reports the entry point');
ok(listing.stdout.includes('0x1000  00 00 34 12'), 'the listing shows address and bytes');
ok(listing.stdout.includes('LI R0, 0x1234'), 'the listing shows the mnemonic');

console.log('\n--- strict errors ---');
const source = fs.readFileSync(path.join(fixtures, 'simple.flow'));
const corrupted = Buffer.from(source);
corrupted[corrupted.length - 1] ^= 0xff;
const corruptedFile = path.join(tmp, 'corrupted.flow');
fs.writeFileSync(corruptedFile, corrupted);
const corruptedRes = run([corruptedFile]);
ok(corruptedRes.status !== 0, 'rejects a corrupted hash');
ok(/SHA-256/.test(corruptedRes.stderr), 'explains the hash mismatch');
const noVerify = disassemble(corruptedFile, ['--no-verify']);
ok(noVerify.res.status === 0, '--no-verify skips the hash check');

const truncatedFile = path.join(tmp, 'truncated.flow');
fs.writeFileSync(truncatedFile, source.subarray(0, 12));
const truncatedRes = run([truncatedFile]);
ok(truncatedRes.status !== 0, 'rejects a truncated header');

const unknownFile = path.join(tmp, 'unknown.flow');
const unknown = Buffer.from(source);
// PROGRAM starts at the user origin (0x1000); corrupt its first opcode.
unknown[segmentFileOffset(unknown, 4)] = 0xf0;
fs.writeFileSync(unknownFile, withHash(unknown));
const unknownRes = disassemble(unknownFile);
ok(unknownRes.res.status !== 0, 'an unknown opcode fails the decode');
ok(
  unknownRes.json &&
    unknownRes.json.diagnostics.some(diagnostic => diagnostic.message.includes('opcode desconocido 0xF0')),
  'reports the unknown opcode'
);
ok(unknownRes.json && unknownRes.json.instructions.some(instruction => instruction.mnemonic === '.byte'), 'emits .byte');
const unknownListing = run([unknownFile]);
ok(unknownListing.stdout.includes('.byte 0xF0'), 'the listing shows the .byte');

const outOfRegionFile = path.join(tmp, 'out-of-region.flow');
const outOfRegion = Buffer.from(source);
outOfRegion.writeUInt16LE(0x2000, segmentTocOffset(outOfRegion, 4) + 2);
fs.writeFileSync(outOfRegionFile, withHash(outOfRegion));
const outOfRegionRes = run([outOfRegionFile]);
ok(outOfRegionRes.status !== 0, 'rejects a program outside the code region');
ok(/región de código/.test(outOfRegionRes.stderr), 'explains the code region');

console.log('\n--- determinism ---');
const first = run([path.join(fixtures, 'firmware.flow'), '--json']);
const second = run([path.join(fixtures, 'firmware.flow'), '--json']);
ok(first.stdout === second.stdout, 'two runs produce the same JSON');

fs.rmSync(tmp, { recursive: true, force: true });

console.log(`\n${passed} passed, ${failed} failed\n`);
if (failed > 0) {
  process.exitCode = 1;
}

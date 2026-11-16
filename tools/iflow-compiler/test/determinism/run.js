#!/usr/bin/env node
'use strict';

// Determinism / drift guard for the committed .flow fixtures.
//
// This is the compiler half of the compiler -> VM integration: the JVM tests
// (BiosBootTest, IrqDeliveryTest, FirmwareIntegrationTest) execute exactly the
// bytes of test/<name>.flow. If the assembler changes and nobody regenerates the
// fixtures, those tests would keep passing against stale bytes. Here we reassemble
// every fixture with the real CLI and assert the output is byte-identical to the
// committed artefact, so any drift fails loudly.
//
// It also pins the format-2 container (IFLOW-AUD-037/038): the magic number, the
// header version, the debug-section presence, and the SHA-256 over the payload.
//
// Run: npm run build && npm run test:determinism

const { spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const testDir = path.resolve(__dirname, '..');
const root = path.resolve(testDir, '..'); // tools/iflow-compiler
const cli = path.join(root, 'dist', 'cli.js');

if (!fs.existsSync(cli)) {
  console.error(`Missing ${cli}\nRun "npm run build" before "npm run test:determinism".`);
  process.exit(2);
}

const MAGIC = 0x464c4f57; // "FLOW"
const LAYOUT_MAGIC = 0x524f4d32; // "ROM2"
const HEADER_SIZE = 32;
const TOC_ENTRY_SIZE = 16;
const HASH_SIZE = 32;

function assemble(source, extra = []) {
  const tag = extra.length ? extra.join('').replace(/[^a-z0-9]/gi, '') : 'plain';
  const out = path.join(os.tmpdir(), `iflow-determinism-${path.basename(source, '.flowasm')}-${tag}.flow`);
  const res = spawnSync(process.execPath, [cli, source, '-o', out, ...extra], { encoding: 'utf8' });
  if (res.status !== 0) {
    throw new Error(`assembler exited ${res.status}: ${(res.stderr || res.stdout || '').trim()}`);
  }
  return fs.readFileSync(out);
}

/** Returns a list of ROM2 container violations (empty when the file is well-formed). */
function formatProblems(buf, expectDebug) {
  const problems = [];

  const magic = buf.readUInt32BE(0);
  if (magic !== MAGIC) problems.push(`magic 0x${magic.toString(16)} != 0x464c4f57`);
  if (buf[4] !== 2) problems.push(`formatVersion ${buf[4]} != 2`);
  if (buf.readUInt32BE(16) !== LAYOUT_MAGIC) {
    problems.push(`layoutMagic 0x${buf.readUInt32BE(16).toString(16)} != 0x524f4d32`);
  }

  const tocOffset = buf.readUInt16BE(10);
  const tocCount = buf.readUInt16BE(12);
  const payloadSize = buf.readUInt32BE(20);
  if (payloadSize !== buf.length - HASH_SIZE) {
    problems.push(`payloadSize ${payloadSize} != file-32 (${buf.length - HASH_SIZE})`);
  }
  if (tocOffset !== HEADER_SIZE) problems.push(`tocOffset ${tocOffset} != ${HEADER_SIZE}`);
  if (tocCount < 1 || tocCount > 16) problems.push(`tocCount ${tocCount} out of range`);
  const tocEnd = tocOffset + tocCount * TOC_ENTRY_SIZE;
  if (tocEnd > payloadSize) problems.push(`TOC ends at ${tocEnd} > payload ${payloadSize}`);

  const types = [];
  const ranges = [];
  let hasDebug = false;
  for (let i = 0; i < tocCount; i++) {
    const base = tocOffset + i * TOC_ENTRY_SIZE;
    if (base + TOC_ENTRY_SIZE > buf.length) {
      problems.push(`TOC entry ${i} truncated`);
      break;
    }
    const type = buf[base];
    const fileOffset = buf.readUInt32BE(base + 4);
    const size = buf.readUInt16BE(base + 8);
    const memSize = buf.readUInt16BE(base + 10);
    types.push(type);
    if (fileOffset < tocEnd || fileOffset + size > payloadSize) {
      problems.push(`segment ${i} file range ${fileOffset}+${size} outside payload`);
    }
    if (size > 0) {
      for (const [otherOffset, otherSize] of ranges) {
        if (fileOffset < otherOffset + otherSize && otherOffset < fileOffset + size) {
          problems.push(`segment ${i} overlaps another segment's file range`);
        }
      }
      ranges.push([fileOffset, size]);
    }
    // Only loadable segments reserve memory; metadata (CAPABILITIES/DEBUG)
    // must keep memSize at zero.
    if (type >= 1 && type <= 5) {
      if (memSize < size) problems.push(`segment ${i} memSize ${memSize} < size ${size}`);
    } else if (memSize !== 0) {
      problems.push(`metadata segment ${i} reserves memory (memSize ${memSize})`);
    }
    if (type === 7) hasDebug = true;
  }
  if (!types.includes(1)) problems.push('missing BIOS segment');
  if (!types.includes(4)) problems.push('missing PROGRAM segment');
  if (expectDebug && !hasDebug) problems.push('expected a DEBUG segment, found none');
  if (!expectDebug && hasDebug) problems.push('unexpected DEBUG segment');

  const hash = buf.subarray(buf.length - HASH_SIZE);
  const computed = crypto.createHash('sha256').update(buf.subarray(0, buf.length - HASH_SIZE)).digest();
  if (!hash.equals(computed)) problems.push('SHA-256 mismatch over the payload');

  return problems;
}

/** Returns the bytes of the first segment of the given type, or null. */
function segmentData(buf, wantedType) {
  const tocOffset = buf.readUInt16BE(10);
  const tocCount = buf.readUInt16BE(12);
  for (let i = 0; i < tocCount; i++) {
    const base = tocOffset + i * TOC_ENTRY_SIZE;
    if (buf[base] !== wantedType) continue;
    const fileOffset = buf.readUInt32BE(base + 4);
    const size = buf.readUInt16BE(base + 8);
    return buf.subarray(fileOffset, fileOffset + size);
  }
  return null;
}

let failed = 0;

// 1. The five committed fixtures must be exactly what the CLI produces today.
const fixtures = ['firmware', 'simple', 'main_only', 'irq', 'bios_screen'];
for (const name of fixtures) {
  const source = path.join(testDir, `${name}.flowasm`);
  const committed = fs.readFileSync(path.join(testDir, `${name}.flow`));

  let produced;
  try {
    produced = assemble(source);
  } catch (e) {
    failed++;
    console.error(`FAIL ${name}: ${e.message}`);
    continue;
  }

  if (!produced.equals(committed)) {
    failed++;
    console.error(
      `FAIL ${name}: ${name}.flow (${committed.length} B) differs from CLI output (${produced.length} B)`);
    console.error('       regenerate the fixture and commit it with the assembler change');
    continue;
  }

  const problems = formatProblems(produced, false);
  if (problems.length) {
    failed++;
    console.error(`FAIL ${name}.flow: ${problems.join('; ')}`);
  } else {
    console.log(`ok   ${name}.flow (byte-identical, ${committed.length} B)`);
  }
}

// 2. With -d the file must carry a debug section (and still hash correctly).
try {
  const debugged = assemble(path.join(testDir, 'simple.flowasm'), ['-d']);
  const problems = formatProblems(debugged, true);
  const debugBytes = segmentData(debugged, 7);
  const debugLength = debugBytes ? debugBytes.length : 0;
  const debugText = debugBytes ? debugBytes.toString('utf8') : '';
  if (!debugText.includes('SYM ')) problems.push('debug section has no symbol table');

  if (problems.length) {
    failed++;
    console.error(`FAIL simple.flow (-d): ${problems.join('; ')}`);
  } else {
    console.log(`ok   simple.flow -d (debug section, ${debugLength} B)`);
  }
} catch (e) {
  failed++;
  console.error(`FAIL simple.flow (-d): ${e.message}`);
}

console.log(`\n${failed === 0 ? 'all fixture and format checks passed' : `${failed} check(s) failed`}`);
process.exit(failed ? 1 : 0);

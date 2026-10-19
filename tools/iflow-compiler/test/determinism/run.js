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

function assemble(source, extra = []) {
  const tag = extra.length ? extra.join('').replace(/[^a-z0-9]/gi, '') : 'plain';
  const out = path.join(os.tmpdir(), `iflow-determinism-${path.basename(source, '.flowasm')}-${tag}.flow`);
  const res = spawnSync(process.execPath, [cli, source, '-o', out, ...extra], { encoding: 'utf8' });
  if (res.status !== 0) {
    throw new Error(`assembler exited ${res.status}: ${(res.stderr || res.stdout || '').trim()}`);
  }
  return fs.readFileSync(out);
}

/** Returns a list of format-2 container violations (empty when the file is well-formed). */
function formatProblems(buf, expectDebug) {
  const problems = [];

  const magic = buf.readUInt32BE(0);
  if (magic !== MAGIC) problems.push(`magic 0x${magic.toString(16)} != 0x464c4f57`);

  if (buf[4] !== 2) problems.push(`formatVersion ${buf[4]} != 2`);

  const codeLength = buf.readUInt16BE(10);
  const debugLength = buf.readUInt16BE(16);
  if (expectDebug && debugLength === 0) problems.push('expected a debug section, found none');
  if (!expectDebug && debugLength !== 0) problems.push(`unexpected debug section (${debugLength} B)`);

  if (buf.length < 20 + codeLength + 32) problems.push('file shorter than header + code + hash');

  const hash = buf.subarray(buf.length - 32);
  const computed = crypto.createHash('sha256').update(buf.subarray(0, buf.length - 32)).digest();
  if (!hash.equals(computed)) problems.push('SHA-256 mismatch over the payload');

  return problems;
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
  const debugLength = debugged.readUInt16BE(16);
  const headerEnd = 20;
  const codeLength = debugged.readUInt16BE(10);
  const constantsLength = debugged.readUInt16BE(12);
  const capabilitiesCount = debugged.readUInt8(14);
  let debugStart = headerEnd + codeLength + constantsLength * 2;
  for (let i = 0; i < capabilitiesCount; i++) {
    while (debugged[debugStart] !== 0) debugStart++;
    debugStart++;
  }
  const debugText = debugged.subarray(debugStart, debugStart + debugLength).toString('utf8');
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

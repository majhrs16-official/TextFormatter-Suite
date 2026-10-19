#!/usr/bin/env node
// Determinism / drift guard for the committed .flow fixtures.
//
// This is the compiler half of the compiler -> VM integration: the JVM tests
// (BiosBootTest, IrqDeliveryTest, FirmwareIntegrationTest) execute exactly the
// bytes of test/<name>.flow. If the assembler changes and nobody regenerates the
// fixtures, those tests would keep passing against stale bytes. Here we reassemble
// every fixture with the real CLI and assert the output is byte-identical to the
// committed artefact, so any drift fails loudly.
//
// Run: npm run build && npm run test:determinism
const { spawnSync } = require('node:child_process');
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

const fixtures = ['firmware', 'simple', 'main_only', 'irq', 'bios_screen'];
let failed = 0;

for (const name of fixtures) {
  const source = path.join(testDir, `${name}.flowasm`);
  const committed = path.join(testDir, `${name}.flow`);
  const out = path.join(os.tmpdir(), `iflow-determinism-${name}.flow`);

  const res = spawnSync(process.execPath, [cli, source, '-o', out], { encoding: 'utf8' });
  if (res.status !== 0) {
    failed++;
    console.error(`FAIL ${name}: assembler exited ${res.status}`);
    console.error((res.stderr || res.stdout || '').trim());
    continue;
  }

  const produced = fs.readFileSync(out);
  const expected = fs.readFileSync(committed);
  if (!produced.equals(expected)) {
    failed++;
    console.error(
      `FAIL ${name}: ${name}.flow (${expected.length} B) differs from CLI output (${produced.length} B)`);
    console.error('       regenerate the fixture and commit it with the assembler change');
  } else {
    console.log(`ok   ${name}.flow (byte-identical, ${expected.length} B)`);
  }
}

console.log(`\n${fixtures.length - failed}/${fixtures.length} fixtures byte-identical`);
process.exit(failed ? 1 : 0);

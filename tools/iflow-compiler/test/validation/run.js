#!/usr/bin/env node
'use strict';

// Assembles every fixture listed in cases.json and asserts the expected
// outcome (success, or failure with a specific diagnostic substring).
//
// Requires the compiled CLI at ../../dist/cli.js — run "npm run build" first.

const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const dir = __dirname;
const cli = path.resolve(dir, '..', '..', 'dist', 'cli.js');
const cases = JSON.parse(fs.readFileSync(path.join(dir, 'cases.json'), 'utf8'));

if (!fs.existsSync(cli)) {
  console.error(`Missing ${cli}\nRun "npm run build" before "npm run test:validation".`);
  process.exit(2);
}

const outFile = path.join(os.tmpdir(), 'iflow-validation.flow');
let failed = 0;

for (const c of cases) {
  const args = [cli, path.join(dir, c.file), '-o', outFile];
  if (c.flags) args.push(...c.flags);
  const res = spawnSync(process.execPath, args, { encoding: 'utf8' });
  const output = `${res.stdout || ''}${res.stderr || ''}`;
  const outcomeOk = c.expect === 'ok' ? res.status === 0 : res.status !== 0;
  const matchOk = !c.match || output.includes(c.match);

  // Optional byte-level assertion over the code section of the produced .flow.
  // Header: [6..7] = codeLength, code starts at offset 16.
  let bytesOk = true;
  let actualCodeHex = '';
  if (c.codeHex) {
    if (res.status === 0) {
      const buf = fs.readFileSync(outFile);
      const codeLength = (buf[6] << 8) | buf[7];
      actualCodeHex = buf.subarray(16, 16 + codeLength).toString('hex');
      bytesOk = actualCodeHex === c.codeHex;
    } else {
      bytesOk = false;
    }
  }

  if (outcomeOk && matchOk && bytesOk) {
    console.log(`ok   ${c.file}`);
  } else {
    failed++;
    const expected = `${c.expect}${c.match ? ` matching "${c.match}"` : ''}`;
    console.error(`FAIL ${c.file} (expected ${expected}, exit ${res.status})`);
    if (!bytesOk) {
      console.error(`       expected code ${c.codeHex}`);
      console.error(`       actual   code ${actualCodeHex || '<unavailable>'}`);
    }
    console.error(output.trim().split('\n').map(l => `       ${l}`).join('\n'));
  }
}

console.log(`\n${cases.length - failed}/${cases.length} cases passed`);
process.exit(failed ? 1 : 0);

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
let total = cases.length;

for (const c of cases) {
  const args = [cli, path.join(dir, c.file), '-o', outFile];
  if (c.flags) args.push(...c.flags);
  const res = spawnSync(process.execPath, args, { encoding: 'utf8' });
  const output = `${res.stdout || ''}${res.stderr || ''}`;
  const outcomeOk = c.expect === 'ok' ? res.status === 0 : res.status !== 0;
  const matchOk = !c.match || output.includes(c.match);

  // Optional byte-level assertion over the PROGRAM segment of the produced .flow.
  // ROM2 layout: 32-byte header, then 16-byte TOC entries.
  let bytesOk = true;
  let actualCodeHex = '';
  if (c.codeHex) {
    if (res.status === 0) {
      const buf = fs.readFileSync(outFile);
      const tocOffset = buf.readUInt16BE(10);
      const tocCount = buf.readUInt16BE(12);
      const program = Array.from({ length: tocCount }, (_, i) => tocOffset + i * 16)
        .find(base => buf[base] === 4);
      if (program === undefined) {
        bytesOk = false;
      } else {
        const fileOffset = buf.readUInt32BE(program + 4);
        const size = buf.readUInt16BE(program + 8);
        actualCodeHex = buf.subarray(fileOffset, fileOffset + size).toString('hex');
        bytesOk = actualCodeHex === c.codeHex;
      }
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

// Generated case: the constants page is 4 KiB, so 2049 words cannot fit.
const overflowFile = path.join(os.tmpdir(), 'iflow-constants-overflow.flowasm');
const overflowSource = Array.from({ length: 2049 }, (_, i) => `.const c${i}, 0`)
  .concat('HALT')
  .join('\n') + '\n';
fs.writeFileSync(overflowFile, overflowSource);
const overflow = spawnSync(process.execPath, [cli, overflowFile, '-o', outFile], {
  encoding: 'utf8',
});
const overflowOutput = `${overflow.stdout || ''}${overflow.stderr || ''}`;
total++;
if (overflow.status !== 0 && overflowOutput.includes('Constants section too large')) {
  console.log('ok   constants-overflow.flowasm');
} else {
  failed++;
  console.error(`FAIL constants-overflow.flowasm (expected error, exit ${overflow.status})`);
  console.error(overflowOutput.trim().split('\n').map(l => `       ${l}`).join('\n'));
}

console.log(`\n${total - failed}/${total} cases passed`);
process.exit(failed ? 1 : 0);

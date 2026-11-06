#!/usr/bin/env node
/* tests/unit/versionLabels.test.cjs — regression: no stale pre-1.0 suite labels */

const fs = require('fs');
const path = require('path');

const dir = path.join(__dirname, '..', '..');
const files = [
  'index.html',
  'prototype.html',
  ...fs
    .readdirSync(path.join(dir, 'js'))
    .filter((name) => name.endsWith('.js'))
    .map((name) => path.join('js', name)),
];

const forbidden = [
  { pattern: /\b2\.1\.0\b/, label: '2.1.0' },
  { pattern: /\bv2\.1\b/, label: 'v2.1' },
  { pattern: /\b3\.0\.0\b/, label: '3.0.0' },
  { pattern: /TextFormatterSuite\/2\.1/, label: 'TextFormatterSuite/2.1' },
];

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

console.log('\n=== Suite version labels ===\n');

for (const file of files) {
  const content = fs.readFileSync(path.join(dir, file), 'utf8');
  for (const { pattern, label } of forbidden) {
    assert(!pattern.test(content), `${file} no contiene ${label}`);
  }
}

console.log('\n=== SUMMARY ===');
console.log('Passed: ' + passed);
console.log('Failed: ' + failed);
process.exit(failed > 0 ? 1 : 0);

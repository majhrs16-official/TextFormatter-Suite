#!/usr/bin/env node
/* tests/unit/suiteArchive.test.cjs — unit tests for Suite.suiteArchive */

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const dir = path.join(__dirname, '..', '..');
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
  runScripts: 'outside-only',
  url: 'https://localhost/',
});
const { window } = dom;
for (const g of ['window', 'document', 'console', 'Uint8Array', 'ArrayBuffer', 'DataView']) {
  if (!(g in global)) {
    global[g] = window[g];
  }
}
window.TextEncoder = globalThis.TextEncoder;
window.TextDecoder = globalThis.TextDecoder;
window.eval(fs.readFileSync(path.join(dir, 'js', 'zip.js'), 'utf8'));
window.eval(fs.readFileSync(path.join(dir, 'js', 'suiteArchive.js'), 'utf8'));
const Suite = window.Suite;
const Archive = Suite.suiteArchive;

const encoder = new TextEncoder();
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

function assertThrows(fn, pattern, message) {
  try {
    fn();
  } catch (error) {
    const ok = pattern.test(String(error && error.message));
    assert(ok, message + (ok ? '' : ' — unexpected message: ' + error.message));
    return;
  }
  assert(false, message + ' — did not throw');
}

function entry(filename, data) {
  return { filename, data };
}

function bytesOf(length) {
  const out = new Uint8Array(length);
  let state = 0x9e3779b9;
  for (let i = 0; i < length; i++) {
    state = (state * 1664525 + 1013904223) >>> 0;
    out[i] = state >>> 24;
  }
  return out;
}

console.log('\n=== Suite.suiteArchive Unit Tests ===\n');

console.log('--- normalizePath ---');
assertEq(Archive.normalizePath('config\\suite\\config.yml'), 'config/suite/config.yml', 'normalizes backslashes');
assertEq(
  Archive.normalizePath('./config//suite/./config.yml'),
  'config/suite/config.yml',
  'removes dots and empty segments'
);
assertEq(Archive.normalizePath('TextFormatter-Suite/config/'), 'TextFormatter-Suite/config', 'strips a trailing slash');
assertThrows(() => Archive.normalizePath('../config/x.yml'), /escapes the root/, 'rejects zip-slip');
assertThrows(() => Archive.normalizePath('/etc/passwd'), /absolute/, 'rejects absolute paths');
assertThrows(() => Archive.normalizePath('C:/config/x.yml'), /drive-qualified/, 'rejects drive-qualified paths');
assertThrows(() => Archive.normalizePath('config/\0/x.yml'), /NUL/, 'rejects NUL bytes');
assertThrows(() => Archive.normalizePath(''), /empty archive path/, 'rejects empty paths');

console.log('\n--- predicates ---');
assert(Archive.isGarbage('__MACOSX/._config.yml'), 'detects __MACOSX garbage');
assert(Archive.isGarbage('config/.DS_Store'), 'detects .DS_Store garbage');
assert(Archive.isGarbage('config/Thumbs.db'), 'detects Thumbs.db garbage');
assert(!Archive.isGarbage('config/suite/config.yml'), 'keeps real files');
assert(Archive.isExcluded('manager/library/packages/suite-x/1.0.0/x.jar'), 'excludes installed packages');
assert(Archive.isExcluded('manager/library/status.tmp'), 'excludes temporary files by suffix');
assert(Archive.isExcluded('journal/operation.log'), 'excludes the journal');
assert(Archive.isExcluded('backup/op/config.yml'), 'excludes backups');
assert(Archive.isExcluded('tmp/op/config.yml'), 'excludes tmp');
assert(Archive.isExcluded('manager/runtime/modules.status'), 'excludes runtime state');
assert(!Archive.isExcluded('manager/library/status'), 'keeps the package database');
assert(Archive.isSuiteFile('config/iflow-16/policy.flow'), 'config files belong to the suite');
assert(Archive.isSuiteFile('manager/library/status'), 'the package database belongs to the suite');
assert(!Archive.isSuiteFile('manager/repo.yml'), 'other manager files are outside the selection');

console.log('\n--- detectRoot ---');
assertEq(Archive.detectRoot([]), '', 'an empty archive has no root');
assertEq(Archive.detectRoot(['config/a.yml', 'manager/library/status']), '', 'files at the root need no prefix');
assertEq(Archive.detectRoot(['TextFormatter-Suite/config/a.yml']), 'TextFormatter-Suite/', 'detects the fixed prefix');
assertThrows(
  () => Archive.detectRoot(['other/config/a.yml']),
  /unexpected archive root/,
  'rejects an unknown single root'
);
assertThrows(
  () => Archive.detectRoot(['TextFormatter-Suite/config/a.yml', 'config/b.yml']),
  /mixed archive root/,
  'rejects mixed roots'
);

console.log('\n--- import: root and selection ---');
const withPrefix = Archive.fromEntries([
  entry('TextFormatter-Suite/config/suite/config.yml', 'pipeline:\n  - route\n'),
  entry('TextFormatter-Suite/config/iflow-16/policy.flow', new Uint8Array([1, 2, 3])),
  entry('TextFormatter-Suite/manager/library/status', 'suite-textformatter 1.0.0\n'),
]);
assertEq(
  withPrefix.paths(),
  ['config/iflow-16/policy.flow', 'config/suite/config.yml', 'manager/library/status'],
  'imports the prefixed archive and strips the root'
);
assertEq(withPrefix.root, 'TextFormatter-Suite/', 'records the detected root');
assertEq(withPrefix.getText('config/suite/config.yml'), 'pipeline:\n  - route\n', 'reads text as UTF-8');
assertEq(Array.from(withPrefix.get('config/iflow-16/policy.flow')), [1, 2, 3], 'preserves binary bytes');

const withoutPrefix = Archive.fromEntries([
  entry('config/suite/config.yml', 'pipeline:\n  - route\n'),
  entry('config/iflow-16/policy.flow', new Uint8Array([1, 2, 3])),
  entry('manager/library/status', 'suite-textformatter 1.0.0\n'),
]);
assertEq(withoutPrefix.paths(), withPrefix.paths(), 'a ZIP without the prefix produces the same archive');
assertEq(withoutPrefix.root, '', 'records the rootless form');

console.log('\n--- import: ignored content ---');
const ignored = Archive.fromEntries([
  entry('TextFormatter-Suite/config/suite/config.yml', 'ok'),
  entry('TextFormatter-Suite/__MACOSX/._config.yml', 'junk'),
  entry('TextFormatter-Suite/config/.DS_Store', 'junk'),
  entry('TextFormatter-Suite/manager/library/packages/x/1.0.0/x.jar', 'jar'),
  entry('TextFormatter-Suite/journal/op.log', 'log'),
  entry('TextFormatter-Suite/manager/repo.yml', 'repo'),
]);
assertEq(ignored.paths(), ['config/suite/config.yml'], 'keeps only the selected suite artifacts');
assertEq(
  ignored.summary().ignored,
  { garbage: 2, excluded: 2, outside: 1 },
  'counts garbage, excluded and outside files'
);

const directories = Archive.fromEntries([
  entry('TextFormatter-Suite/config/', new Uint8Array(0)),
  { filename: 'TextFormatter-Suite/config/dir/', data: new Uint8Array(0), isDirectory: true },
  entry('TextFormatter-Suite/config/suite/config.yml', 'ok'),
]);
assertEq(directories.paths(), ['config/suite/config.yml'], 'ignores directory entries');

console.log('\n--- import: security ---');
assertThrows(() => Archive.fromEntries([entry('../evil.yml', 'x')]), /escapes the root/, 'rejects zip-slip entries');
assertThrows(
  () => Archive.fromEntries([entry('config/a.yml', 'a'), entry('./config/a.yml', 'b')]),
  /duplicate/,
  'rejects duplicates after normalization'
);
assertThrows(
  () => Archive.fromEntries([entry('config/link', 'target'), { ...entry('config/link2', 'x'), isSymlink: true }]),
  /symlink/,
  'rejects symlink entries'
);
assertThrows(
  () => Archive.fromEntries([entry('TextFormatter-Suite/TextFormatter-Suite/config/a.yml', 'x')]),
  /nested 'TextFormatter-Suite\/'/,
  'rejects a nested suite root'
);

console.log('\n--- export ---');
const archive = Archive.fromEntries([
  entry('config/suite/config.yml', 'b'),
  entry('config/chattranslator/config.yml', 'a'),
]);
const exported = archive.toEntries();
assertEq(
  exported.map(item => item.name),
  ['TextFormatter-Suite/config/chattranslator/config.yml', 'TextFormatter-Suite/config/suite/config.yml'],
  'exports every entry with the fixed prefix in deterministic order'
);
assertEq(
  exported.map(item => new TextDecoder().decode(item.data)),
  ['a', 'b'],
  'exports the original bytes'
);
archive.files.set('manager/library/packages/x/1.0.0/x.jar', encoder.encode('jar'));
archive.files.set('config/notes.txt.tmp', encoder.encode('tmp'));
assertEq(archive.toEntries().length, 2, 'skips excluded paths even if they reach the map');

console.log('\n--- round trip ---');
const binary = bytesOf(4096);
const original = Archive.fromEntries([
  entry('TextFormatter-Suite/config/suite/config.yml', 'outputs:\n  - sync.discord\n'),
  entry('TextFormatter-Suite/config/iflow-wasm/policy.wasm', binary),
  entry('TextFormatter-Suite/config/iflow-wasm/policy.sha256', 'abc123\n'),
]);
const zipBytes = original.toZip();
assert(zipBytes instanceof window.ArrayBuffer, 'toZip returns an ArrayBuffer');
const restored = Archive.fromZip(zipBytes);
assertEq(restored.paths(), original.paths(), 'round trip preserves every path');
assertEq(
  Array.from(restored.get('config/iflow-wasm/policy.wasm')),
  Array.from(binary),
  'round trip preserves binary bytes'
);
assertEq(restored.getText('config/iflow-wasm/policy.sha256'), 'abc123\n', 'round trip preserves text bytes');

const reader = new Suite.zip.Reader(zipBytes);
const fromReader = Archive.fromReader(reader);
assertEq(fromReader.paths(), original.paths(), 'fromReader accepts a Suite.zip.Reader');

console.log('\n--- mutable archive ---');
const mutable = Archive.fromEntries([entry('config/suite/config.yml', 'old')]);
assert(mutable.has('config/suite/config.yml'), 'has finds a path');
mutable.setText('config/suite/config.yml', 'new');
assertEq(mutable.getText('config/suite/config.yml'), 'new', 'setText replaces the content');
mutable.set('config/suite/commands.yml', encoder.encode('cmd'));
assert(mutable.has('config/suite/commands.yml'), 'set adds a new path');
assertEq(mutable.summary().files, 2, 'summary counts files');
assert(mutable.delete('config/suite/commands.yml'), 'delete removes a path');
assert(!mutable.has('config/suite/commands.yml'), 'delete removes the entry');
assertEq(mutable.get('config/suite/commands.yml'), null, 'get returns null for a missing path');
assertThrows(() => mutable.set('manager/library/packages/x.jar', 'x'), /excluded/, 'set rejects excluded paths');
assertThrows(() => mutable.set('manager/repo.yml', 'x'), /outside/, 'set rejects files outside the selection');

console.log('\n--- limits ---');
const limited = new Archive.SuiteArchive(new Map(), {
  limits: { maxEntries: 1, maxEntryBytes: 4, maxTotalBytes: 5 },
});
limited.set('config/a.yml', 'abcd');
assertThrows(() => limited.set('config/b.yml', 'x'), /entries/, 'set enforces the entry limit');
assertThrows(() => limited.set('config/a.yml', 'abcde'), /per-file limit/, 'set enforces the per-file limit');
const total = new Archive.SuiteArchive(new Map(), { limits: { maxTotalBytes: 5 } });
total.set('config/a.yml', 'abc');
assertThrows(() => total.set('config/b.yml', 'def'), /total limit/, 'set enforces the total limit');
assertThrows(
  () =>
    Archive.fromEntries([entry('config/a.yml', 'abc'), entry('config/b.yml', 'def')], { limits: { maxTotalBytes: 5 } }),
  /total limit/,
  'import enforces the total limit'
);
assertThrows(
  () => Archive.fromEntries([entry('config/a.yml', 'abcde')], { limits: { maxEntryBytes: 4 } }),
  /per-file limit/,
  'import enforces the per-file limit'
);
assertThrows(
  () => Archive.fromEntries([entry('config/a.yml', 'a'), entry('config/b.yml', 'b')], { limits: { maxEntries: 1 } }),
  /limit of 1 entries/,
  'import enforces the entry limit'
);

console.log('\n--- clone ---');
const cloneSource = Archive.fromEntries([entry('config/a.yml', 'a'), entry('manager/library/status', 's')]);
const cloneCopy = cloneSource.clone();
cloneCopy.set('config/b.yml', 'b');
cloneCopy.setText('config/a.yml', 'changed');
assertEq(cloneSource.paths(), ['config/a.yml', 'manager/library/status'], 'clone does not add paths to the original');
assertEq(cloneSource.getText('config/a.yml'), 'a', 'clone does not mutate original bytes');
assertEq(cloneCopy.paths(), ['config/a.yml', 'config/b.yml', 'manager/library/status'], 'clone accepts new paths');
assertEq(cloneCopy.getText('config/a.yml'), 'changed', 'clone carries the mutation');

console.log('\n' + passed + ' passed, ' + failed + ' failed');
process.exit(failed ? 1 : 0);

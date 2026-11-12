#!/usr/bin/env node
/* tests/unit/zip.test.cjs — unit tests for Suite.zip.Reader */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
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
const Suite = window.Suite;
const Zip = Suite.zip;

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

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c;
  }
  return table;
})();

function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function buildZip(entries) {
  const locals = [];
  const central = [];
  let offset = 0;

  for (const spec of entries) {
    const nameBytes = encoder.encode(spec.name);
    const raw = typeof spec.data === 'string' ? encoder.encode(spec.data) : spec.data;
    const method = spec.method || 0;
    const compressed = spec.compressed || raw;
    const crc = spec.crc !== undefined ? spec.crc : crc32(raw);
    const descriptor = Boolean(spec.descriptor);
    const flags = (spec.flags || 0) | 0x0800;
    const localOffset = offset;
    const local = new Uint8Array(30 + nameBytes.length + compressed.length + (descriptor ? 16 : 0));
    const view = new DataView(local.buffer);

    view.setUint32(0, 0x04034b50, true);
    view.setUint16(4, 20, true);
    view.setUint16(6, flags, true);
    view.setUint16(8, method, true);
    view.setUint16(10, 0, true);
    view.setUint16(12, 0x21, true);
    view.setUint32(14, descriptor ? 0 : crc, true);
    view.setUint32(18, descriptor ? 0 : compressed.length, true);
    view.setUint32(22, descriptor ? 0 : raw.length, true);
    view.setUint16(26, nameBytes.length, true);
    view.setUint16(28, 0, true);
    local.set(nameBytes, 30);
    local.set(compressed, 30 + nameBytes.length);

    if (descriptor) {
      const at = 30 + nameBytes.length + compressed.length;
      view.setUint32(at, 0x08074b50, true);
      view.setUint32(at + 4, crc, true);
      view.setUint32(at + 8, compressed.length, true);
      view.setUint32(at + 12, raw.length, true);
    }

    locals.push(local);
    central.push({
      nameBytes,
      flags,
      method,
      crc,
      compressedSize: compressed.length,
      uncompressedSize: raw.length,
      externalAttributes: spec.externalAttributes || 0,
      localOffset,
    });
    offset += local.length;
  }

  const centralOffset = offset;
  const centralSize = central.reduce((sum, entry) => sum + 46 + entry.nameBytes.length, 0);
  const out = new Uint8Array(centralOffset + centralSize + 22);
  let pos = 0;
  for (const local of locals) {
    out.set(local, pos);
    pos += local.length;
  }
  for (const entry of central) {
    const view = new DataView(out.buffer, pos);
    view.setUint32(0, 0x02014b50, true);
    view.setUint16(4, 20, true);
    view.setUint16(6, 20, true);
    view.setUint16(8, entry.flags, true);
    view.setUint16(10, entry.method, true);
    view.setUint16(12, 0, true);
    view.setUint16(14, 0x21, true);
    view.setUint32(16, entry.crc, true);
    view.setUint32(20, entry.compressedSize, true);
    view.setUint32(24, entry.uncompressedSize, true);
    view.setUint16(28, entry.nameBytes.length, true);
    view.setUint32(38, entry.externalAttributes, true);
    view.setUint32(42, entry.localOffset, true);
    out.set(entry.nameBytes, pos + 46);
    pos += 46 + entry.nameBytes.length;
  }
  const eocd = new DataView(out.buffer, pos);
  eocd.setUint32(0, 0x06054b50, true);
  eocd.setUint16(8, central.length, true);
  eocd.setUint16(10, central.length, true);
  eocd.setUint32(12, centralSize, true);
  eocd.setUint32(16, centralOffset, true);
  return { bytes: out, centralOffset };
}

function patchU32(bytes, offset, value) {
  const copy = bytes.slice();
  new DataView(copy.buffer).setUint32(offset, value, true);
  return copy;
}

function bytesOf(length) {
  const out = new Uint8Array(length);
  let state = 0x12345678;
  for (let i = 0; i < length; i++) {
    state = (state * 1664525 + 1013904223) >>> 0;
    out[i] = state >>> 24;
  }
  return out;
}

console.log('\n=== Suite.zip.Reader Unit Tests ===\n');

console.log('--- empty archive ---');
const empty = buildZip([]);
const emptyReader = new Zip.Reader(empty.bytes);
assertEq(emptyReader.entries.length, 0, 'empty archive has no entries');

console.log('\n--- STORE ---');
const text = 'outputs:\n  - sync.discord\n';
const binary = new Uint8Array(256);
for (let i = 0; i < 256; i++) {
  binary[i] = i;
}
const stored = buildZip([
  { name: 'config/suite/config.yml', data: text },
  { name: 'config/iflow-vm/policy.flow', data: binary },
]);
const storedReader = new Zip.Reader(stored.bytes);
assertEq(storedReader.entries.length, 2, 'reads both entries');
assertEq(storedReader.entries[0].filename, 'config/suite/config.yml', 'reads the entry name');
assertEq(new TextDecoder().decode(storedReader.entries[0].getData()), text, 'STORE text round-trips');
assertEq(Array.from(storedReader.entries[1].getData()), Array.from(binary), 'STORE binary round-trips');
assert(storedReader.entries[0].isDirectory === false, 'a file is not a directory');

console.log('\n--- DEFLATE ---');
const repetitive = encoder.encode('abcabcabcabc'.repeat(20000));
const random = bytesOf(120000);
for (const [label, raw] of [
  ['repetitive', repetitive],
  ['pseudo-random', random],
]) {
  const compressed = new Uint8Array(zlib.deflateRawSync(Buffer.from(raw)));
  const deflated = buildZip([{ name: label + '.bin', data: raw, method: 8, compressed }]);
  const reader = new Zip.Reader(deflated.bytes);
  assertEq(Array.from(reader.entries[0].getData()), Array.from(raw), 'DEFLATE ' + label + ' round-trips');
}

console.log('\n--- data descriptor ---');
const descriptor = buildZip([{ name: 'descriptor.txt', data: 'hello', descriptor: true }]);
const descriptorReader = new Zip.Reader(descriptor.bytes);
assertEq(
  new TextDecoder().decode(descriptorReader.entries[0].getData()),
  'hello',
  'entry with data descriptor round-trips'
);

console.log('\n--- directories and UTF-8 ---');
const directory = buildZip([
  { name: 'config/', data: new Uint8Array(0) },
  { name: 'config/añadido.yml', data: 'ok' },
]);
const directoryReader = new Zip.Reader(directory.bytes);
assert(directoryReader.entries[0].isDirectory, 'directory entry is detected');
assertEq(directoryReader.entries[0].getData().length, 0, 'directory entry yields empty data');
assertEq(directoryReader.entries[1].filename, 'config/añadido.yml', 'UTF-8 names are decoded');
assertEq(new TextDecoder().decode(directoryReader.entries[1].getData()), 'ok', 'UTF-8 entry round-trips');

const symlink = buildZip([{ name: 'link', data: 'target', externalAttributes: 0xa1ff0000 }]);
assert(new Zip.Reader(symlink.bytes).entries[0].isSymlink, 'detects Unix symlink entries');

console.log('\n--- iteration ---');
const names = [];
for (const entry of storedReader) {
  names.push(entry.filename);
}
assertEq(names, ['config/suite/config.yml', 'config/iflow-vm/policy.flow'], 'reader is iterable');

console.log('\n--- integrity ---');
const badCrc = buildZip([{ name: 'bad.txt', data: 'hello', crc: 0x12345678 }]);
assertThrows(() => new Zip.Reader(badCrc.bytes).entries[0].getData(), /CRC mismatch/, 'rejects a bad CRC');

const compressedHello = new Uint8Array(zlib.deflateRawSync(Buffer.from('hello')));
const truncated = buildZip([{ name: 'hello.txt', data: 'hello', method: 8, compressed: compressedHello }]);
assertThrows(
  () => new Zip.Reader(patchU32(truncated.bytes, truncated.centralOffset + 20, 9999)).entries[0].getData(),
  /truncated/,
  'rejects truncated entry data'
);

const wrongSize = buildZip([{ name: 'hello.txt', data: 'hello', method: 8, compressed: compressedHello }]);
assertThrows(
  () => new Zip.Reader(patchU32(wrongSize.bytes, wrongSize.centralOffset + 24, 6)).entries[0].getData(),
  /uncompressed size/,
  'rejects an uncompressed size mismatch'
);

console.log('\n--- format errors ---');
assertThrows(() => new Zip.Reader(new Uint8Array(64)), /end of central directory/, 'rejects a non-ZIP file');
assertThrows(
  () => new Zip.Reader(buildZip([{ name: 'a.txt', data: 'a', flags: 1 }]).bytes),
  /encrypted/,
  'rejects encrypted entries'
);
assertThrows(
  () => new Zip.Reader(buildZip([{ name: 'a.txt', data: 'a', method: 12 }]).bytes),
  /unsupported compression method 12/,
  'rejects unknown compression methods'
);
const zip64 = patchU32(empty.bytes, empty.bytes.length - 22 + 12, 0xffffffff);
assertThrows(() => new Zip.Reader(zip64), /ZIP64/, 'rejects ZIP64 archives');

function withZip64Locator(archive) {
  const eocdAt = archive.length - 22;
  const locator = new Uint8Array(20);
  new DataView(locator.buffer).setUint32(0, 0x07064b50, true);
  const out = new Uint8Array(archive.length + 20);
  out.set(archive.subarray(0, eocdAt), 0);
  out.set(locator, eocdAt);
  out.set(archive.subarray(eocdAt), eocdAt + 20);
  return out;
}

assertThrows(
  () => new Zip.Reader(withZip64Locator(empty.bytes)),
  /ZIP64/,
  'rejects the ZIP64 end-of-central-directory locator'
);
const maxCount = patchU32(
  patchU32(empty.bytes, empty.bytes.length - 22 + 8, 0xffff),
  empty.bytes.length - 22 + 10,
  0xffff
);
assertThrows(
  () => new Zip.Reader(maxCount),
  /truncated central directory/,
  'does not treat a plain 0xFFFF entry count as ZIP64'
);
assertThrows(
  () =>
    new Zip.Reader(
      buildZip([
        { name: 'a.txt', data: 'a' },
        { name: 'a.txt', data: 'b' },
      ]).bytes
    ),
  /duplicate/,
  'rejects duplicate entry names'
);

console.log('\n--- limits ---');
assertThrows(
  () =>
    new Zip.Reader(
      buildZip([
        { name: 'a.txt', data: 'a' },
        { name: 'b.txt', data: 'b' },
      ]).bytes,
      {
        limits: { maxEntries: 1 },
      }
    ),
  /entries; the limit is 1/,
  'rejects too many entries'
);
assertThrows(
  () => new Zip.Reader(buildZip([{ name: 'a.txt', data: 'hello' }]).bytes, { limits: { maxEntryBytes: 4 } }),
  /per-file limit/,
  'rejects an entry over the per-file limit'
);
assertThrows(
  () =>
    new Zip.Reader(
      buildZip([
        { name: 'a.txt', data: 'abc' },
        { name: 'b.txt', data: 'def' },
      ]).bytes,
      {
        limits: { maxTotalBytes: 5 },
      }
    ),
  /total uncompressed limit/,
  'rejects an archive over the total limit'
);

const bombRaw = new Uint8Array(1024 * 1024 + 1);
const bombCompressed = new Uint8Array(zlib.deflateRawSync(Buffer.from(bombRaw)));
const bomb = buildZip([{ name: 'bomb.bin', data: bombRaw, method: 8, compressed: bombCompressed }]);
assertThrows(() => new Zip.Reader(bomb.bytes), /compression ratio/, 'rejects an excessive compression ratio');
const allowed = new Zip.Reader(bomb.bytes, { limits: { maxCompressionRatio: 100000 } });
assertEq(allowed.entries.length, 1, 'the ratio limit is configurable');

console.log('\n--- inflateRaw ---');
const direct = new Uint8Array(zlib.deflateRawSync(Buffer.from(repetitive)));
assertEq(Array.from(Zip.inflateRaw(direct)), Array.from(repetitive), 'inflateRaw decodes a raw DEFLATE stream');

console.log('\n--- deflateRaw ---');
function roundTrips(label, data) {
  const compressed = Zip.deflateRaw(data);
  const ours = Zip.inflateRaw(compressed);
  assertEq(Array.from(ours), Array.from(data), 'deflateRaw round-trips through inflateRaw: ' + label);
  const reference = new Uint8Array(zlib.inflateRawSync(Buffer.from(compressed)));
  assertEq(Array.from(reference), Array.from(data), 'deflateRaw round-trips through zlib: ' + label);
  return compressed;
}

const emptyCompressed = roundTrips('empty', new Uint8Array(0));
roundTrips('one byte', new Uint8Array([0x41]));
roundTrips('two bytes', new Uint8Array([0x00, 0xff]));
roundTrips('repetitive text', encoder.encode('hello hello hello hello hello'));
const longText = encoder.encode('config-version: 1.0\nname: chat\nrules:\n  - id: a\n    match: hello\n'.repeat(400));
const longCompressed = roundTrips('long YAML', longText);
assert(longCompressed.length < longText.length / 10, 'deflateRaw compresses repetitive YAML');
assert(
  longCompressed.length <= zlib.deflateRawSync(Buffer.from(longText), { level: 9 }).length * 1.25,
  'deflateRaw is close to zlib level 9'
);
const pseudoRandom = new Uint8Array(4096);
for (let i = 0; i < pseudoRandom.length; i++) {
  pseudoRandom[i] = (i * 2654435761) >>> 24;
}
roundTrips('pseudo-random bytes', pseudoRandom);
const ramp = new Uint8Array(1024);
for (let i = 0; i < ramp.length; i++) {
  ramp[i] = i & 0xff;
}
roundTrips('binary ramp', ramp);
assertEq(Array.from(Zip.deflateRaw(longText)), Array.from(longCompressed), 'deflateRaw is deterministic');

console.log('\n--- build with DEFLATE ---');
const compressibleName = 'TextFormatter-Suite/config/suite/config.yml';
const incompressible = new Uint8Array(require('crypto').randomBytes(512));
const built = new Uint8Array(
  Zip.build([
    { name: compressibleName, data: longText },
    { name: 'TextFormatter-Suite/binary.dat', data: incompressible },
  ])
);
const builtReader = new Zip.Reader(built);
assertEq(builtReader.entries.length, 2, 'build writes both entries');
const yamlEntry = builtReader.entries.find(entry => entry.filename === compressibleName);
const binaryEntry = builtReader.entries.find(entry => entry.filename === 'TextFormatter-Suite/binary.dat');
assertEq(yamlEntry.method, 8, 'build compresses a compressible entry with DEFLATE');
assertEq(binaryEntry.method, 0, 'build stores an incompressible entry');
assertEq(Array.from(yamlEntry.getData()), Array.from(longText), 'the compressed entry round-trips');
assertEq(Array.from(binaryEntry.getData()), Array.from(incompressible), 'the stored entry round-trips');
const rebuilt = new Uint8Array(
  Zip.build([
    { name: compressibleName, data: longText },
    { name: 'TextFormatter-Suite/binary.dat', data: incompressible },
  ])
);
assertEq(Array.from(rebuilt), Array.from(built), 'build is deterministic');

console.log('\n' + passed + ' passed, ' + failed + ' failed');
process.exit(failed ? 1 : 0);

#!/usr/bin/env node
/* tests/unit/rom.test.cjs — unit tests for Suite.rom */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { JSDOM } = require('jsdom');

const dir = path.join(__dirname, '..', '..');
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
  runScripts: 'outside-only',
  url: 'https://localhost/',
});
const { window } = dom;
window.eval(fs.readFileSync(path.join(dir, 'js', 'rom.js'), 'utf8'));
const Rom = window.Suite.rom;

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

function hashOf(bytes) {
  return crypto.createHash('sha256').update(Buffer.from(bytes)).digest();
}

function buildFlow(over) {
  const options = Object.assign(
    {
      format: 2,
      isa: 2,
      abi: 2,
      firmware: 1,
      entryPoint: 0,
      code: [0x00, 0x00],
      constants: [],
      capabilities: [],
      debug: '',
    },
    over || {}
  );
  const caps = [];
  for (const cap of options.capabilities) {
    caps.push(...Buffer.from(cap, 'utf8'), 0);
  }
  const debug = Buffer.from(options.debug, 'utf8');
  const size = 20 + options.code.length + options.constants.length * 2 + caps.length + debug.length;
  const out = Buffer.alloc(size);
  out.writeUInt32BE(0x464c4f57, 0);
  out[4] = options.format;
  out[5] = options.isa;
  out[6] = options.abi;
  out[7] = options.firmware;
  out.writeUInt16BE(options.entryPoint, 8);
  out.writeUInt16BE(options.code.length, 10);
  out.writeUInt16BE(options.constants.length, 12);
  out[14] = options.capabilities.length;
  out[15] = 0;
  out.writeUInt16BE(debug.length, 16);
  out.writeUInt16BE(0, 18);
  let offset = 20;
  Buffer.from(options.code).copy(out, offset);
  offset += options.code.length;
  for (const value of options.constants) {
    out.writeUInt16BE(value, offset);
    offset += 2;
  }
  Buffer.from(caps).copy(out, offset);
  offset += caps.length;
  debug.copy(out, offset);
  return Buffer.concat([out, hashOf(out)]);
}

function validWasm(extraSection) {
  const header = Buffer.from([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]);
  if (!extraSection) {
    return header;
  }
  return Buffer.concat([header, Buffer.from(extraSection)]);
}

console.log('\n=== Suite.rom Unit Tests ===\n');

console.log('--- SHA-256 y base64 ---');
assertEq(
  Rom.sha256Hex(new Uint8Array(0)),
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
  'sha256 vacío'
);
assertEq(
  Rom.sha256Hex(new TextEncoder().encode('abc')),
  'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
  'sha256 de abc'
);
const bytes = new Uint8Array([0, 1, 2, 250, 251, 252, 253, 254, 255]);
assertEq(Rom.fromBase64(Rom.toBase64(bytes)), bytes, 'base64 round-trip');
assertEq(Rom.toBase64(new Uint8Array([0])), 'AA==', 'base64 de un byte');
assertEq(Rom.fromBase64('AA==').length, 1, 'base64 con padding');
let threw = false;
try {
  Rom.fromBase64('***');
} catch (error) {
  threw = error.name === 'RomError';
}
assert(threw, 'base64 inválido se rechaza');

console.log('\n--- sidecar ---');
const sidecarHash = 'a'.repeat(64);
assertEq(Rom.sidecarHash(sidecarHash + '  policy.wasm\n'), sidecarHash, 'lee el hash del sidecar');
assertEq(Rom.sidecarHash('texto sin hash'), null, 'sin hash devuelve null');
assertEq(
  Rom.formatSidecar(sidecarHash, 'policy.wasm'),
  sidecarHash + '  policy.wasm\n',
  'formatea el sidecar como sha256sum'
);
assertEq(
  Rom.sidecarPathFor('config/iflow-wasm/policy.wasm'),
  'config/iflow-wasm/policy.sha256',
  'deriva la ruta del sidecar'
);

console.log('\n--- .flow válido ---');
const flow = buildFlow({ capabilities: ['log'], debug: 'main' });
const flowResult = Rom.validateFlow(flow);
assert(flowResult.ok, 'acepta un .flow v2 válido — ' + flowResult.error);
assertEq(flowResult.meta.formatVersion, 2, 'lee formatVersion');
assertEq(flowResult.meta.isaVersion, 2, 'lee isaVersion');
assertEq(flowResult.meta.abiVersion, 2, 'lee abiVersion');
assertEq(flowResult.meta.codeLength, 2, 'lee codeLength');
assertEq(flowResult.meta.capabilitiesCount, 1, 'lee capabilitiesCount');
assertEq(flowResult.meta.hash, Rom.sha256Hex(flow.subarray(0, flow.length - 32)), 'expone el hash');
const entry = Rom.entryFromBytes('config/iflow-16/policy.flow', flow);
assert(entry.valid, 'entryFromBytes acepta el .flow');
assertEq(entry.kind, 'flow', 'detecta el tipo flow');
assert(entry.canonical, 'marca la ruta canónica');
assertEq(Rom.fromBase64(entry.data).length, flow.length, 'conserva los bytes');

console.log('\n--- .flow inválido ---');
const badMagic = Buffer.from(flow);
badMagic.writeUInt32BE(0x11111111, 0);
assert(!Rom.validateFlow(badMagic).ok, 'rechaza magic inválido');
assert(/magic/.test(Rom.validateFlow(badMagic).error), 'explica el magic');
assert(!Rom.validateFlow(buildFlow({ format: 1 })).ok, 'rechaza formato 1');
assert(!Rom.validateFlow(buildFlow({ isa: 3 })).ok, 'rechaza ISA futura');
assert(!Rom.validateFlow(buildFlow({ abi: 1 })).ok, 'rechaza ABI distinta');
assert(!Rom.validateFlow(buildFlow({ firmware: 2 })).ok, 'rechaza firmware futuro');
assert(!Rom.validateFlow(buildFlow({ entryPoint: 2 })).ok, 'rechaza entryPoint fuera del código');
assert(!Rom.validateFlow(buildFlow({ code: [] })).ok, 'rechaza código vacío');
assert(!Rom.validateFlow(Buffer.from([0, 1, 2])).ok, 'rechaza datos cortos');

const badHash = Buffer.from(flow);
badHash[badHash.length - 1] ^= 0xff;
assert(!Rom.validateFlow(badHash).ok, 'rechaza hash que no coincide');
assert(/SHA-256/.test(Rom.validateFlow(badHash).error), 'explica el hash');

const extra = Buffer.concat([flow, Buffer.from([0])]);
assert(!Rom.validateFlow(extra).ok, 'rechaza bytes extra');
assert(/bytes extra/.test(Rom.validateFlow(extra).error), 'explica los bytes extra');

const truncated = flow.subarray(0, flow.length - 1);
assert(!Rom.validateFlow(truncated).ok, 'rechaza trailer truncado');

const unterminated = Buffer.alloc(20);
unterminated.writeUInt32BE(0x464c4f57, 0);
unterminated[4] = 2;
unterminated[5] = 2;
unterminated[6] = 2;
unterminated[7] = 1;
unterminated[14] = 1;
assert(!Rom.validateFlow(unterminated).ok, 'rechaza capacidad sin NUL');
assert(/terminador NUL/.test(Rom.validateFlow(unterminated).error), 'explica la capacidad sin NUL');

console.log('\n--- .wasm ---');
const wasm = validWasm();
const wasmResult = Rom.validateWasm(wasm);
assert(wasmResult.ok, 'acepta un .wasm mínimo — ' + wasmResult.error);
assertEq(wasmResult.meta.version, 1, 'lee la versión wasm');
assertEq(wasmResult.meta.sections, 0, 'cuenta secciones');
assertEq(wasmResult.meta.hash, Rom.sha256Hex(wasm), 'calcula el hash wasm');

const wasmSection = validWasm([0x01, 0x01, 0x00]);
assert(Rom.validateWasm(wasmSection).ok, 'acepta una sección con longitud válida');
assertEq(Rom.validateWasm(wasmSection).meta.sections, 1, 'cuenta una sección');

const wasmBadMagic = Buffer.from(wasm);
wasmBadMagic[0] = 0xff;
assert(!Rom.validateWasm(wasmBadMagic).ok, 'rechaza magic wasm inválido');
assert(!Rom.validateWasm(Buffer.from([0x00, 0x61, 0x73, 0x6d, 0x02, 0x00, 0x00, 0x00])).ok, 'rechaza versión 2');
assert(!Rom.validateWasm(validWasm([0x01, 0x05, 0x00])).ok, 'rechaza sección truncada');
assert(!Rom.validateWasm(Buffer.from([0, 1])).ok, 'rechaza datos cortos');

const wasmEntry = Rom.entryFromBytes('config/iflow-wasm/policy.wasm', wasm);
assert(wasmEntry.valid, 'entryFromBytes acepta el wasm sin sidecar');
assertEq(wasmEntry.sha256, Rom.sha256Hex(wasm), 'calcula sha256 de la entrada');
assertEq(wasmEntry.sidecarPath, 'config/iflow-wasm/policy.sha256', 'asigna el sidecar canónico');

const withSidecar = Rom.entryFromBytes('config/iflow-wasm/policy.wasm', wasm, {
  sidecarText: Rom.formatSidecar(wasmEntry.sha256, 'policy.wasm'),
});
assert(withSidecar.valid, 'acepta un sidecar que coincide');

const wrongSidecar = Rom.entryFromBytes('config/iflow-wasm/policy.wasm', wasm, {
  sidecarText: '0'.repeat(64) + '  policy.wasm\n',
});
assert(!wrongSidecar.valid, 'rechaza un sidecar que no coincide');
assert(/no coincide/.test(wrongSidecar.error), 'explica el sidecar');

const missingSidecar = Rom.entryFromBytes('config/iflow-wasm/policy.wasm', wasm, { requireSidecar: true });
assert(!missingSidecar.valid, 'exige sidecar al importar');
assert(/falta el sidecar/.test(missingSidecar.error), 'explica el sidecar ausente');

console.log('\n--- rutas y clones ---');
assert(Rom.isRomPath('config/iflow-16/policy.flow'), 'reconoce un .flow canónico');
assert(Rom.isRomPath('config/iflow-16/policy-copy.flow'), 'reconoce un .flow clonado');
assert(Rom.isRomPath('config/iflow-wasm/policy.wasm'), 'reconoce un .wasm canónico');
assert(!Rom.isRomPath('config/iflow-16/readme.txt'), 'ignora un archivo que no es ROM');
assert(!Rom.isRomPath('config/other/policy.flow'), 'ignora un .flow fuera de iflow-16');
assert(Rom.isSidecarPath('config/iflow-wasm/policy.sha256'), 'reconoce un sidecar');
assertEq(Rom.kindOfPath('a.flow'), 'flow', 'kindOfPath flow');
assertEq(Rom.kindOfPath('a.wasm'), 'wasm', 'kindOfPath wasm');
assertEq(Rom.kindOfPath('a.txt'), null, 'kindOfPath desconocido');
assertEq(
  Rom.clonePath('config/iflow-16/policy.flow', new Set()),
  'config/iflow-16/policy-copy.flow',
  'sugiere un nombre de clon'
);
assertEq(
  Rom.clonePath('config/iflow-16/policy.flow', new Set(['config/iflow-16/policy-copy.flow'])),
  'config/iflow-16/policy-copy-2.flow',
  'evita colisiones de clon'
);

console.log('\n' + passed + ' passed, ' + failed + ' failed\n');
if (failed > 0) {
  process.exitCode = 1;
}

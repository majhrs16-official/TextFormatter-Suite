#!/usr/bin/env node
/* tests/unit/suiteMapping.test.cjs — unit tests for Suite.suiteMapping */

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
window.TextEncoder = globalThis.TextEncoder;
window.TextDecoder = globalThis.TextDecoder;
for (const code of [
  'yaml.js',
  'yamlCst.js',
  'packageDb.js',
  'rom.js',
  'zip.js',
  'suiteArchive.js',
  'model.js',
  'suiteMapping.js',
]) {
  window.eval(fs.readFileSync(path.join(dir, 'js', code), 'utf8'));
}
const Suite = window.Suite;
const Mapping = Suite.suiteMapping;
const Archive = Suite.suiteArchive;
const Db = Suite.packageDb;
const Rom = Suite.rom;

const encoder = new TextEncoder();
const decoder = new TextDecoder();
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

function bytesEq(actual, expected, message) {
  const same = Buffer.compare(Buffer.from(actual), Buffer.from(expected)) === 0;
  assert(same, message + (same ? '' : ' — bytes differ'));
}

function entry(filename, data) {
  return { filename, data };
}

function archiveOf(entries) {
  return Archive.fromEntries(entries);
}

function text(archive, path) {
  const bytes = archive.get(path);
  return bytes ? decoder.decode(bytes) : null;
}

function hashOf(bytes) {
  return crypto.createHash('sha256').update(Buffer.from(bytes)).digest();
}

function buildFlow(over) {
  const options = Object.assign({ code: [0x00, 0x00], capabilities: ['log'], debug: 'main' }, over || {});
  const code = Buffer.from(options.code);
  const segments = [{ type: 4, flags: 1 | 4 | 8, vaddr: 0, data: code, memSize: code.length }];

  const capParts = [];
  for (const cap of options.capabilities) {
    capParts.push(Buffer.from(cap, 'utf8'), Buffer.from([0]));
  }
  const caps = capParts.length > 0 ? Buffer.concat(capParts) : Buffer.alloc(0);
  if (caps.length > 0) {
    segments.push({ type: 6, flags: 0, vaddr: 0, data: caps, memSize: 0 });
  }

  const debug = Buffer.from(options.debug, 'utf8');
  if (debug.length > 0) {
    segments.push({ type: 7, flags: 0, vaddr: 0, data: debug, memSize: 0 });
  }

  const tocOffset = 32;
  let cursor = tocOffset + segments.length * 16;
  for (const segment of segments) {
    segment.fileOffset = cursor;
    cursor += segment.data.length;
  }
  const payloadSize = cursor;
  const out = Buffer.alloc(payloadSize);
  out.writeUInt32BE(0x464c4f57, 0);
  out[4] = 2;
  out[5] = 2;
  out[6] = 2;
  out[7] = 1;
  out.writeUInt16LE(0, 8);
  out.writeUInt16LE(tocOffset, 10);
  out.writeUInt16LE(segments.length, 12);
  out.writeUInt16LE(0, 14);
  out.writeUInt32BE(0x524f4d32, 16);
  out.writeUInt32LE(payloadSize, 20);
  for (let i = 0; i < segments.length; i++) {
    const segment = segments[i];
    const base = tocOffset + i * 16;
    out[base] = segment.type;
    out[base + 1] = segment.flags;
    out.writeUInt16LE(segment.vaddr, base + 2);
    out.writeUInt32LE(segment.fileOffset, base + 4);
    out.writeUInt16LE(segment.data.length, base + 8);
    out.writeUInt16LE(segment.memSize, base + 10);
    segment.data.copy(out, segment.fileOffset);
  }
  return new Uint8Array(Buffer.concat([out, hashOf(out)]));
}

function buildWasm() {
  return new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]);
}

console.log('\n=== Suite.suiteMapping Unit Tests ===\n');

console.log('--- paths ---');
assertEq(Mapping.FIXED.config, 'config/suite/config.yml', 'config path');
assertEq(Mapping.FIXED.graph, 'config/iflow-firewall/rules.yml', 'graph path');
assertEq(Mapping.channelPath('chat.global'), 'config/textformatter/channels/chat.global.yml', 'channel path');
assertEq(Mapping.translatorPath('google'), 'config/chattranslator/translators/google.yml', 'translator path');
assertEq(Mapping.syncPath('discord'), 'config/sync-discord/config.yml', 'discord path');
assertEq(Mapping.syncPath('tcp'), 'config/sync-socket/tcp.yml', 'tcp path');
assertEq(Mapping.syncPath('udp'), 'config/sync-socket/udp.yml', 'udp path');
assertEq(Mapping.syncPath('websocket'), 'config/sync-websocket/config.yml', 'websocket path');
assertEq(Mapping.syncPath('bukkit'), 'config/sync-bukkit/config.yml', 'bukkit path');
assertEq(Mapping.syncPath('fabric'), 'config/sync-fabric/config.yml', 'fabric path');
assertEq(Mapping.syncPath('unknown'), null, 'unknown sync key has no path');

console.log('\n--- serializeYaml ---');
assertEq(Mapping.serializeYaml({}), 'config-version: 1.0\n', 'empty object becomes only config-version');
assertEq(
  Mapping.serializeYaml({ 'quick-look': false, 'config-version': 9 }),
  'quick-look: false\nconfig-version: 1.0\n',
  'strips the old version and appends the canonical one'
);
assertEq(
  Mapping.serializeYaml({ enabled: true }),
  'enabled: true\nconfig-version: 1.0\n',
  'appends config-version to a map'
);
assert(!Mapping.serializeYaml([{ id: 'x' }]).includes('config-version'), 'does not append config-version to an array');
assertEq(Mapping.stripMeta({ a: 1, 'config-version': 1 }), { a: 1 }, 'stripMeta removes only the root version');

const sha256 = 'abcdef0123456789'.repeat(4);
const statusText = [
  'Name: iflow',
  'Version: 1.0.0',
  'Channel: stable',
  'Type: PACKAGE',
  'Dependencies: ',
  'Arch: none',
  'Platform: common',
  'URL: ',
  'OS: none',
  'SHA256: ' + sha256,
  'Size: 48213',
  'Files: ',
  '  iflow.jar\t48213\t' + sha256 + '\tiflow',
  '',
].join('\n');

console.log('\n--- fromSuite: known files ---');
const source = archiveOf([
  entry('config/suite/config.yml', 'quick-look: false\noutputs:\n  - sync.discord\nconfig-version: 1.0\n'),
  entry(
    'config/textformatter/channels/chat.global.yml',
    '# comentario del canal\nname: chat.global\npermission: cht.chat.global\nmessages:\n  - "<green>hola</green>"\nconfig-version: 1.0\n'
  ),
  entry(
    'config/iflow-firewall/rules.yml',
    'guard:\n  max-steps: 99\nnodes:\n  - id: n1\n    kind: input\nedges: []\nconfig-version: 1.0\n'
  ),
  entry('config/chattranslator/translators/google.yml', 'provider: google\nactive: false\nconfig-version: 1.0\n'),
  entry(
    'config/sync-discord/config.yml',
    'enabled: true\ntoken: secret\nchannel: 42\nformat: "{sender}: {content}"\ntransport: jda\nconfig-version: 1.0\n'
  ),
  entry(
    'config/sync-socket/tcp.yml',
    'enabled: true\nremoteHost: 127.0.0.1\nremotePort: 9093\nlocalPort: 0\nconfig-version: 1.0\n'
  ),
  entry('config/suite/messages.yml', 'prefix: "[suite]"\n'),
  entry('config/iflow-16/policy.flow', new Uint8Array([0, 1, 2, 3])),
  entry('manager/library/status', statusText),
]);
const imported = Mapping.fromSuite(source);
const state = imported.state;
assertEq(state.config['quick-look'], false, 'hydrates config');
assertEq(state.config.outputs, ['sync.discord'], 'hydrates config outputs');
assertEq(state.config['config-version'], undefined, 'does not leak config-version into the model');
assertEq(state.channels['chat.global'].permission, 'cht.chat.global', 'hydrates a channel');
assertEq(state.channels['chat.global'].messages, ['<green>hola</green>'], 'hydrates channel messages');
assertEq(state.channels['chat.global'].name, 'chat.global', 'keeps the channel name');
assertEq(state.graph.guard['max-steps'], 99, 'hydrates the graph');
assertEq(state.graph.nodes.length, 1, 'hydrates graph nodes');
assertEq(state.translators.google.active, false, 'hydrates a translator');
assertEq(state.sync.discord.token, 'secret', 'hydrates discord');
assertEq(state.sync.discord.channel, 42, 'hydrates the discord channel');
assertEq(state.sync.tcp.remoteHost, '127.0.0.1', 'hydrates tcp remoteHost');
assertEq(state.sync.tcp.remotePort, 9093, 'hydrates tcp remotePort');
assertEq(state.extra['config/suite/messages.yml'], 'prefix: "[suite]"\n', 'keeps unmapped text in extra');
assertEq(state.packages.length, 1, 'hydrates the package database');
assertEq(state.packages[0].name, 'iflow', 'hydrates a package');
assertEq(state.packages[0].files.length, 1, 'hydrates the package file list');
assert(imported.consumed.includes('config/suite/config.yml'), 'marks config as consumed');
assert(imported.consumed.includes('manager/library/status'), 'marks the package database as consumed');
assertEq(imported.deferred.length, 0, 'no files are deferred');
assert(imported.opaque.includes('config/iflow-16/policy.flow'), 'keeps the invalid VM ROM opaque');
assert(
  imported.warnings.some(w => w.path === 'config/iflow-16/policy.flow' && w.message.includes('ROM inválida')),
  'warns about the invalid VM ROM'
);

console.log('\n--- fromSuite: opaque and malformed ---');
const spiel = archiveOf([
  entry('config/iflow-firewall/rules.yml', '- id: drop\n  target: DROP\n  condition: "#content.length() > 0"\n'),
]);
const spielResult = Mapping.fromSuite(spiel);
assert(
  spielResult.warnings.some(w => w.path === 'config/iflow-firewall/rules.yml'),
  'warns about SpEL rules'
);
assert(spielResult.opaque.includes('config/iflow-firewall/rules.yml'), 'keeps SpEL rules opaque');
assertEq(spielResult.state.graph.nodes.length, 8, 'keeps the default editor graph');

const binary = archiveOf([entry('config/unknown.bin', new Uint8Array([0, 255, 0, 1]))]);
const binaryResult = Mapping.fromSuite(binary);
assert(binaryResult.opaque.includes('config/unknown.bin'), 'keeps binary files opaque');
assert(
  binaryResult.warnings.some(w => w.path === 'config/unknown.bin'),
  'warns about binary files'
);

const mismatch = archiveOf([
  entry('config/textformatter/channels/chat.global.yml', 'name: otro.canal\nconfig-version: 1.0\n'),
]);
const mismatchResult = Mapping.fromSuite(mismatch);
assert(
  mismatchResult.warnings.some(w => w.path.includes('chat.global.yml') && w.message.includes('no coincide')),
  'warns when name and filename differ'
);

console.log('\n--- fromSuite/toSuite: ROM ---');
const flowBytes = buildFlow();
const wasmBytes = buildWasm();
const wasmHash = Rom.sha256Hex(wasmBytes);
const romSource = archiveOf([
  entry('config/iflow-16/policy.flow', flowBytes),
  entry('config/iflow-wasm/policy.wasm', wasmBytes),
  entry('config/iflow-wasm/policy.sha256', wasmHash + '  policy.wasm\n'),
]);
const romImport = Mapping.fromSuite(romSource);
assertEq(romImport.state.roms.length, 2, 'hydrates both ROMs');
assertEq(romImport.state.roms[0].kind, 'flow', 'hydrates the VM ROM first');
assertEq(romImport.state.roms[1].kind, 'wasm', 'hydrates the Wasm ROM second');
assertEq(romImport.state.roms[0].sha256, null, 'the flow does not invent a sidecar');
assertEq(romImport.state.roms[1].sha256, wasmHash, 'hydrates the wasm hash');
assertEq(romImport.state.roms[1].sidecarPath, 'config/iflow-wasm/policy.sha256', 'hydrates the sidecar path');
assert(romImport.consumed.includes('config/iflow-16/policy.flow'), 'marks the flow as consumed');
assert(romImport.consumed.includes('config/iflow-wasm/policy.wasm'), 'marks the wasm as consumed');
assert(romImport.consumed.includes('config/iflow-wasm/policy.sha256'), 'marks the sidecar as consumed');

const romUntouched = Mapping.toSuite(romImport.state, romSource, romImport);
assertEq(romUntouched.written, [], 'does not rewrite untouched ROMs');
assert(romUntouched.preserved.includes('config/iflow-16/policy.flow'), 'preserves the untouched flow');
assert(romUntouched.preserved.includes('config/iflow-wasm/policy.wasm'), 'preserves the untouched wasm');
assert(romUntouched.preserved.includes('config/iflow-wasm/policy.sha256'), 'preserves the untouched sidecar');
bytesEq(romSource.get('config/iflow-16/policy.flow'), flowBytes, 'flow bytes are identical');
bytesEq(romSource.get('config/iflow-wasm/policy.wasm'), wasmBytes, 'wasm bytes are identical');

const editedFlow = buildFlow({ code: [0x00, 0x00, 0x00] });
const flowEdit = Mapping.fromSuite(romSource);
flowEdit.state.roms[0].data = Rom.toBase64(editedFlow);
const flowOut = Mapping.toSuite(flowEdit.state, romSource, flowEdit);
assert(flowOut.written.includes('config/iflow-16/policy.flow'), 'writes the edited flow');
bytesEq(romSource.get('config/iflow-16/policy.flow'), editedFlow, 'writes the new flow bytes');

const newWasm = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00]);
const newWasmHash = Rom.sha256Hex(newWasm);
const wasmEdit = Mapping.fromSuite(romSource);
wasmEdit.state.roms[1].data = Rom.toBase64(newWasm);
wasmEdit.state.roms[1].sha256 = newWasmHash;
wasmEdit.state.roms[1].sidecarText = Rom.formatSidecar(newWasmHash, 'policy.wasm');
const wasmOut = Mapping.toSuite(wasmEdit.state, romSource, wasmEdit);
assert(wasmOut.written.includes('config/iflow-wasm/policy.wasm'), 'writes the edited wasm');
assert(wasmOut.written.includes('config/iflow-wasm/policy.sha256'), 'rewrites the sidecar with the new hash');
bytesEq(romSource.get('config/iflow-wasm/policy.wasm'), newWasm, 'writes the new wasm bytes');
assertEq(
  text(romSource, 'config/iflow-wasm/policy.sha256'),
  newWasmHash + '  policy.wasm\n',
  'writes the recalculated sidecar'
);

const badHash = Mapping.fromSuite(romSource);
badHash.state.roms[1].data = Rom.toBase64(newWasm);
badHash.state.roms[1].sha256 = newWasmHash;
badHash.state.roms[1].sidecarText = '0'.repeat(64) + '  policy.wasm\n';
let hashError = null;
try {
  Mapping.toSuite(badHash.state, romSource, badHash);
} catch (error) {
  hashError = error;
}
assert(hashError && /no coincide/.test(hashError.message), 'refuses a wasm whose sidecar does not match');
bytesEq(romSource.get('config/iflow-wasm/policy.wasm'), newWasm, 'leaves the wasm untouched on a hash error');

const noSidecar = archiveOf([entry('config/iflow-wasm/policy.wasm', wasmBytes)]);
const noSidecarImport = Mapping.fromSuite(noSidecar);
assertEq(noSidecarImport.state.roms.length, 0, 'does not hydrate a wasm without a sidecar');
assert(noSidecarImport.opaque.includes('config/iflow-wasm/policy.wasm'), 'keeps a wasm without a sidecar opaque');
assert(
  noSidecarImport.warnings.some(w => w.path === 'config/iflow-wasm/policy.wasm'),
  'warns about the missing sidecar'
);

const cloneSource = archiveOf([
  entry('config/iflow-16/policy.flow', flowBytes),
  entry('config/iflow-wasm/policy.wasm', wasmBytes),
  entry('config/iflow-wasm/policy.sha256', wasmHash + '  policy.wasm\n'),
]);
const cloneImport = Mapping.fromSuite(cloneSource);
const clone = JSON.parse(JSON.stringify(cloneImport.state.roms[1]));
clone.id = 'config/iflow-wasm/policy-copy.wasm';
clone.path = clone.id;
clone.canonical = false;
clone.sidecarPath = 'config/iflow-wasm/policy-copy.sha256';
clone.sidecarText = Rom.formatSidecar(clone.sha256, 'policy-copy.wasm');
cloneImport.state.roms.push(clone);
const cloneOut = Mapping.toSuite(cloneImport.state, cloneSource, cloneImport);
assert(cloneOut.written.includes('config/iflow-wasm/policy-copy.wasm'), 'writes a wasm clone');
assert(cloneOut.written.includes('config/iflow-wasm/policy-copy.sha256'), 'writes the clone sidecar');
bytesEq(cloneSource.get('config/iflow-wasm/policy-copy.wasm'), wasmBytes, 'the clone copies the wasm bytes');
assertEq(
  text(cloneSource, 'config/iflow-wasm/policy-copy.sha256'),
  wasmHash + '  policy-copy.wasm\n',
  'the clone copies the sidecar with its own name'
);

const deleteImport = Mapping.fromSuite(romSource);
deleteImport.state.roms = [];
const deleteOut = Mapping.toSuite(deleteImport.state, romSource, deleteImport);
assert(deleteOut.removed.includes('config/iflow-16/policy.flow'), 'deletes the removed flow');
assert(deleteOut.removed.includes('config/iflow-wasm/policy.wasm'), 'deletes the removed wasm');
assert(deleteOut.removed.includes('config/iflow-wasm/policy.sha256'), 'deletes the removed sidecar');
assert(!romSource.has('config/iflow-16/policy.flow'), 'the flow is gone from the archive');
assert(!romSource.has('config/iflow-wasm/policy.wasm'), 'the wasm is gone from the archive');

console.log('\n--- toSuite: untouched files preserve bytes ---');
const originalConfig = text(source, 'config/suite/config.yml');
const originalChannel = text(source, 'config/textformatter/channels/chat.global.yml');
const originalDiscord = text(source, 'config/sync-discord/config.yml');
const originalMessages = text(source, 'config/suite/messages.yml');
const originalStatus = text(source, 'manager/library/status');
const untouched = Mapping.toSuite(imported.state, source, imported);
assertEq(untouched.written, [], 'writes nothing when nothing changed');
assert(untouched.preserved.includes('config/suite/config.yml'), 'preserves the untouched config');
assert(untouched.preserved.includes('manager/library/status'), 'preserves the untouched package database');
bytesEq(source.get('config/suite/config.yml'), encoder.encode(originalConfig), 'config bytes are identical');
bytesEq(
  source.get('config/textformatter/channels/chat.global.yml'),
  encoder.encode(originalChannel),
  'channel bytes are identical'
);
bytesEq(source.get('config/sync-discord/config.yml'), encoder.encode(originalDiscord), 'sync bytes are identical');
bytesEq(source.get('config/suite/messages.yml'), encoder.encode(originalMessages), 'extra bytes are identical');
bytesEq(source.get('manager/library/status'), encoder.encode(originalStatus), 'package database bytes are identical');
assertEq(text(source, 'config/iflow-16/policy.flow'), '\u0000\u0001\u0002\u0003', 'opaque ROM bytes are identical');

console.log('\n--- toSuite: package database ---');
const packageSource = archiveOf([entry('manager/library/status', statusText)]);
const packageImport = Mapping.fromSuite(packageSource);
packageImport.state.packages[0].version = '1.0.1';
packageImport.state.packages[0].dependencies = ['iflow-16@>=1.0.0'];
packageImport.state.packages[0].extra['Installed-Time'] = '2024-01-01T00:00:00Z';
const packageExport = Mapping.toSuite(packageImport.state, packageSource, packageImport);
assert(packageExport.written.includes('manager/library/status'), 'rewrites the edited package database');
const packageOut = text(packageSource, 'manager/library/status');
assert(packageOut.includes('Version: 1.0.1'), 'writes the edited package version');
assert(packageOut.includes('Dependencies: iflow-16@>=1.0.0'), 'writes the edited dependencies');
assert(packageOut.includes('Installed-Time: 2024-01-01T00:00:00Z'), 'writes the edited extra field');
assertEq(Db.parse(packageOut)[0].files, packageImport.state.packages[0].files, 'preserves the package files');

const addedPackage = Mapping.fromSuite(archiveOf([entry('manager/library/status', statusText)]));
addedPackage.state.packages.push(Db.create());
const addedOut = Mapping.toSuite(
  addedPackage.state,
  archiveOf([entry('manager/library/status', statusText)]),
  addedPackage
);
assert(addedOut.written.includes('manager/library/status'), 'writes a newly added package');
assert(text(addedOut.archive, 'manager/library/status').includes('Name: new-package'), 'serializes the new package');

const emptyPackages = Mapping.fromSuite(archiveOf([entry('manager/library/status', statusText)]));
emptyPackages.state.packages = [];
const emptyOut = Mapping.toSuite(
  emptyPackages.state,
  archiveOf([entry('manager/library/status', statusText)]),
  emptyPackages
);
assert(emptyOut.written.includes('manager/library/status'), 'rewrites the database when all packages are removed');
assertEq(text(emptyOut.archive, 'manager/library/status'), '', 'an empty database serializes as empty text');

const invalidStatus = archiveOf([entry('manager/library/status', '# comentario\n')]);
const invalidImport = Mapping.fromSuite(invalidStatus);
assert(invalidImport.opaque.includes('manager/library/status'), 'keeps an invalid package database opaque');
assertEq(invalidImport.state.packages, null, 'does not hydrate an invalid package database');
assert(
  invalidImport.warnings.some(
    w => w.path === 'manager/library/status' && w.message.includes('base de paquetes inválida')
  ),
  'warns about an invalid package database'
);
invalidImport.state.packages = [Db.create()];
const invalidOut = Mapping.toSuite(invalidImport.state, invalidStatus, invalidImport);
assert(
  invalidOut.warnings.some(w => w.path === 'manager/library/status'),
  'refuses to overwrite an opaque package database'
);
assertEq(
  text(invalidStatus, 'manager/library/status'),
  '# comentario\n',
  'opaque package database bytes are untouched'
);

console.log('\n--- toSuite: edited files are serialized ---');
imported.state.config['quick-look'] = true;
const edited = Mapping.toSuite(imported.state, source, imported);
assert(edited.written.includes('config/suite/config.yml'), 'rewrites the edited config');
const editedConfig = text(source, 'config/suite/config.yml');
assert(editedConfig.includes('quick-look: true'), 'writes the new config value');
assert(editedConfig.trimEnd().endsWith('config-version: 1.0'), 'adds config-version at the end');
assert(!editedConfig.includes('# comentario'), 'does not invent comments');

console.log('\n--- toSuite: CST preserves comments and format ---');
const commentedText = [
  '# cabecera del canal',
  "name: 'chat.global' # nombre visible",
  'permission: cht.chat.global',
  'messages:',
  '  - "<green>hola</green>" # saludo',
  '  - "<gray>adios</gray>"',
  'config-version: 1.0',
  '',
].join('\n');
const commentedSource = archiveOf([entry('config/textformatter/channels/chat.global.yml', commentedText)]);
const commentedImport = Mapping.fromSuite(commentedSource);
commentedImport.state.channels['chat.global'].permission = 'cht.nuevo';
commentedImport.state.channels['chat.global'].messages.push('<yellow>nuevo</yellow>');
const commentedExport = Mapping.toSuite(commentedImport.state, commentedSource, commentedImport);
const commentedOut = text(commentedSource, 'config/textformatter/channels/chat.global.yml');
assert(
  commentedExport.written.includes('config/textformatter/channels/chat.global.yml'),
  'rewrites the edited channel'
);
assertEq(
  commentedOut,
  [
    '# cabecera del canal',
    "name: 'chat.global' # nombre visible",
    'permission: cht.nuevo',
    'messages:',
    '  - "<green>hola</green>" # saludo',
    '  - "<gray>adios</gray>"',
    '  - <yellow>nuevo</yellow>',
    'config-version: 1.0',
    '',
  ].join('\n'),
  'conserva comentarios, comillas, orden y config-version al editar'
);

console.log('\n--- toSuite: unrepresentable edits fail without writing ---');
const atomicConfig = 'quick-look: false\nconfig-version: 1.0\n';
const atomicGraph = [
  'guard:',
  '  # comentario interno',
  '  max-steps: 99',
  'nodes: []',
  'config-version: 1.0',
  '',
].join('\n');
const atomicSource = archiveOf([
  entry('config/suite/config.yml', atomicConfig),
  entry('config/iflow-firewall/rules.yml', atomicGraph),
]);
const atomicImport = Mapping.fromSuite(atomicSource);
atomicImport.state.config['quick-look'] = true;
atomicImport.state.graph.guard = 'plano';
let atomicError = null;
try {
  Mapping.toSuite(atomicImport.state, atomicSource, atomicImport);
} catch (error) {
  atomicError = error;
}
assert(atomicError && atomicError.name === 'SuiteMappingError', 'lanza SuiteMappingError');
assert(
  atomicError && atomicError.message.includes('config/iflow-firewall/rules.yml'),
  'el error identifica la ruta no representable'
);
assertEq(
  text(atomicSource, 'config/suite/config.yml'),
  atomicConfig,
  'no escribe el config válido si otro archivo falla'
);
assertEq(text(atomicSource, 'config/iflow-firewall/rules.yml'), atomicGraph, 'no toca el archivo no representable');

const badSuite = Mapping.fromSuite(
  archiveOf([entry('config/suite/config.yml', 'options:\n  # interno\n  a: 1\nconfig-version: 1.0\n')])
);
const badSuiteArchive = archiveOf([
  entry('config/suite/config.yml', 'options:\n  # interno\n  a: 1\nconfig-version: 1.0\n'),
]);
badSuite.state.config.options = 'plano';
let nestedError = null;
try {
  Mapping.toSuite(badSuite.state, badSuiteArchive, badSuite);
} catch (error) {
  nestedError = error;
}
assert(
  nestedError && nestedError.message.includes('perdería formato'),
  'rechaza convertir una colección comentada en escalar'
);
assertEq(
  text(badSuiteArchive, 'config/suite/config.yml'),
  'options:\n  # interno\n  a: 1\nconfig-version: 1.0\n',
  'la colección comentada queda intacta'
);

console.log('\n--- toSuite: channels add, rename and remove ---');
const channelState = Mapping.fromSuite(
  archiveOf([
    entry(
      'config/textformatter/channels/chat.global.yml',
      'name: chat.global\nmessages:\n  - hola\nconfig-version: 1.0\n'
    ),
  ])
);
const channelArchive = archiveOf([
  entry(
    'config/textformatter/channels/chat.global.yml',
    'name: chat.global\nmessages:\n  - hola\nconfig-version: 1.0\n'
  ),
]);
Suite.model.renameChannel(channelState.state, 'chat.global', 'chat.nuevo');
const renamed = Mapping.toSuite(channelState.state, channelArchive, channelState);
assert(renamed.removed.includes('config/textformatter/channels/chat.global.yml'), 'removes the old channel path');
assert(renamed.written.includes('config/textformatter/channels/chat.nuevo.yml'), 'writes the renamed channel');
assert(!channelArchive.has('config/textformatter/channels/chat.global.yml'), 'old channel file is gone');
assert(channelArchive.has('config/textformatter/channels/chat.nuevo.yml'), 'new channel file exists');

const removedState = Mapping.fromSuite(
  archiveOf([
    entry('config/textformatter/channels/chat.global.yml', 'name: chat.global\nconfig-version: 1.0\n'),
    entry('config/textformatter/channels/chat.hub.yml', 'name: chat.hub\nconfig-version: 1.0\n'),
  ])
);
const removedArchive = archiveOf([
  entry('config/textformatter/channels/chat.global.yml', 'name: chat.global\nconfig-version: 1.0\n'),
  entry('config/textformatter/channels/chat.hub.yml', 'name: chat.hub\nconfig-version: 1.0\n'),
]);
delete removedState.state.channels['chat.hub'];
const removed = Mapping.toSuite(removedState.state, removedArchive, removedState);
assert(removed.removed.includes('config/textformatter/channels/chat.hub.yml'), 'removes a deleted channel');
assert(!removedArchive.has('config/textformatter/channels/chat.hub.yml'), 'deleted channel file is gone');
assert(removedArchive.has('config/textformatter/channels/chat.global.yml'), 'keeps the remaining channel');

const addedState = Mapping.fromSuite(
  archiveOf([entry('config/textformatter/channels/chat.global.yml', 'name: chat.global\nconfig-version: 1.0\n')])
);
const addedArchive = archiveOf([
  entry('config/textformatter/channels/chat.global.yml', 'name: chat.global\nconfig-version: 1.0\n'),
]);
Suite.model.addChannel(addedState.state, 'nuevo.canal', ['hola']);
const added = Mapping.toSuite(addedState.state, addedArchive, addedState);
assert(added.written.includes('config/textformatter/channels/nuevo.canal.yml'), 'writes a new channel');
assert(addedArchive.has('config/textformatter/channels/nuevo.canal.yml'), 'new channel file exists');

console.log('\n--- toSuite: opaque files are never overwritten ---');
const opaqueArchive = archiveOf([entry('config/iflow-firewall/rules.yml', '- id: drop\n  target: DROP\n')]);
const opaqueImport = Mapping.fromSuite(opaqueArchive);
opaqueImport.state.graph.guard['max-steps'] = 1;
const opaqueExport = Mapping.toSuite(opaqueImport.state, opaqueArchive, opaqueImport);
assertEq(
  text(opaqueArchive, 'config/iflow-firewall/rules.yml'),
  '- id: drop\n  target: DROP\n',
  'keeps the SpEL rules'
);
assert(
  opaqueExport.warnings.some(w => w.path === 'config/iflow-firewall/rules.yml'),
  'warns that the opaque graph was not overwritten'
);

console.log('\n--- toSuite: extra add, edit and remove ---');
const extraState = Mapping.fromSuite(archiveOf([entry('config/suite/messages.yml', 'prefix: "[suite]"\n')]));
const extraArchive = archiveOf([entry('config/suite/messages.yml', 'prefix: "[suite]"\n')]);
extraState.state.extra['config/suite/commands.yml'] = 'root: [suite]\n';
const extraAdded = Mapping.toSuite(extraState.state, extraArchive, extraState);
assert(extraAdded.written.includes('config/suite/commands.yml'), 'writes a new extra file');
extraState.state.extra['config/suite/messages.yml'] = 'prefix: "[nuevo]"\n';
Mapping.toSuite(extraState.state, extraArchive, extraState);
assertEq(text(extraArchive, 'config/suite/messages.yml'), 'prefix: "[nuevo]"\n', 'rewrites an edited extra file');
delete extraState.state.extra['config/suite/messages.yml'];
Mapping.toSuite(extraState.state, extraArchive, extraState);
assert(!extraArchive.has('config/suite/messages.yml'), 'removes a deleted extra file');

const guarded = Mapping.fromSuite(
  archiveOf([entry('config/suite/config.yml', 'quick-look: true\nconfig-version: 1.0\n')])
);
const guardedArchive = archiveOf([entry('config/suite/config.yml', 'quick-look: true\nconfig-version: 1.0\n')]);
guarded.state.extra['config/suite/config.yml'] = 'no-debe-ganar\n';
const guardedExport = Mapping.toSuite(guarded.state, guardedArchive, guarded);
assert(
  guardedExport.warnings.some(w => w.path === 'config/suite/config.yml' && w.message.includes('ruta mapeada')),
  'warns when extra tries to overwrite a mapped path'
);
assertEq(
  text(guardedArchive, 'config/suite/config.yml'),
  'quick-look: true\nconfig-version: 1.0\n',
  'extra cannot overwrite a mapped path'
);

console.log('\n--- new export without a mapping ---');
const fresh = Mapping.toSuite(Suite.model.defaults());
const freshPaths = fresh.archive.paths();
assert(freshPaths.includes('config/suite/config.yml'), 'writes config');
assert(freshPaths.includes('config/iflow-firewall/rules.yml'), 'writes the graph');
assert(freshPaths.includes('config/textformatter/channels/chat.global.yml'), 'writes channels');
assert(freshPaths.includes('config/chattranslator/translators/google.yml'), 'writes translators');
assert(freshPaths.includes('config/sync-discord/config.yml'), 'writes discord');
assert(freshPaths.includes('config/sync-socket/tcp.yml'), 'writes tcp');
assert(!freshPaths.includes('manager/library/status'), 'does not invent the package database');
assert(!freshPaths.includes('manifest.json'), 'does not add the editor manifest');
assert(
  text(fresh.archive, 'config/suite/config.yml').trimEnd().endsWith('config-version: 1.0'),
  'config has config-version'
);
assert(
  text(fresh.archive, 'config/iflow-firewall/rules.yml').trimEnd().endsWith('config-version: 1.0'),
  'graph has config-version'
);
assert(
  text(fresh.archive, 'config/textformatter/channels/chat.global.yml').trimEnd().endsWith('config-version: 1.0'),
  'channels have config-version'
);
assert(
  text(fresh.archive, 'config/chattranslator/translators/google.yml').trimEnd().endsWith('config-version: 1.0'),
  'translators have config-version'
);

console.log('\n--- zip round-trip ---');
const roundTripSource = archiveOf([
  entry('TextFormatter-Suite/config/suite/config.yml', 'quick-look: true\n# comentario\nconfig-version: 1.0\n'),
  entry(
    'TextFormatter-Suite/config/textformatter/channels/chat.global.yml',
    'name: chat.global\nconfig-version: 1.0\n'
  ),
  entry('TextFormatter-Suite/config/iflow-wasm/policy.wasm', new Uint8Array([1, 2, 3, 4])),
]);
const roundTripImport = Mapping.fromSuite(roundTripSource);
const roundTripExport = Mapping.toSuite(roundTripImport.state, roundTripSource, roundTripImport);
const zipBytes = roundTripExport.archive.toZip();
const restored = Archive.fromZip(zipBytes);
assertEq(restored.paths(), roundTripSource.paths(), 'round trip preserves the selected paths');
bytesEq(
  restored.get('config/suite/config.yml'),
  roundTripSource.get('config/suite/config.yml'),
  'round trip preserves untouched config bytes'
);
bytesEq(
  restored.get('config/iflow-wasm/policy.wasm'),
  roundTripSource.get('config/iflow-wasm/policy.wasm'),
  'round trip preserves binary ROM bytes'
);

console.log('\n--- summarize ---');
const summarySource = archiveOf([
  entry('config/suite/config.yml', 'quick-look: true\nconfig-version: 1.0\n'),
  entry('config/textformatter/channels/chat.yml', 'name: chat\nconfig-version: 1.0\n'),
  entry('config/iflow-wasm/policy.wasm', new Uint8Array([1, 2, 3])),
  entry('manager/library/status', 'package: textformatter\n'),
  entry('config/unknown/module.yml', 'a: 1\nconfig-version: 1.0\n'),
]);
const summaryImport = Mapping.fromSuite(summarySource);
const summary = Mapping.summarize(summarySource, summaryImport);
assertEq(summary.files, 5, 'summarize counts files');
assertEq(
  summary.modules,
  ['iflow-wasm', 'manager', 'suite', 'textformatter', 'unknown'],
  'summarize lists module directories'
);
assertEq(summary.mapped, summaryImport.consumed.length, 'summarize reports mapped files');
assertEq(summary.opaque, summaryImport.opaque.length, 'summarize reports opaque files');
assertEq(summary.deferred, summaryImport.deferred.length, 'summarize reports deferred files');
assertEq(summary.extra, 1, 'summarize reports extra text files');
assertEq(summary.warnings, summaryImport.warnings.length, 'summarize reports warnings');
assert(summary.bytes > 0, 'summarize reports total bytes');
const bareSummary = Mapping.summarize(summarySource, null);
assertEq(bareSummary.mapped, 0, 'summarize without mapping reports zero mapped files');
assertEq(bareSummary.warnings, 0, 'summarize without mapping reports zero warnings');
assertEq(Mapping.summarize(null, null).files, 0, 'summarize tolerates an absent archive');

console.log('\n--- validateSuite ---');
const validSuite = archiveOf([
  entry('config/suite/config.yml', 'quick-look: true\nconfig-version: 1.0\n'),
  entry('config/textformatter/channels/chat.yml', 'name: chat\nconfig-version: "1.0"\n'),
  entry('config/iflow-wasm/policy.wasm', new Uint8Array([0x00, 0x61, 0x73, 0x6d])),
  entry('manager/library/status', 'Name: x\n'),
]);
const validCheck = Mapping.validateSuite(validSuite, { opaque: [] });
assertEq(validCheck.errors, [], 'accepts a valid suite');
assertEq(validCheck.warnings, [], 'a valid suite has no warnings');
assertEq(validCheck.modules, ['iflow-wasm', 'suite', 'textformatter'], 'lists the modules');

const missingVersion = archiveOf([entry('config/suite/config.yml', 'quick-look: true\n')]);
const missingCheck = Mapping.validateSuite(missingVersion, { opaque: [] });
assertEq(missingCheck.errors.length, 1, 'rejects a missing config-version');
assertEq(missingCheck.errors[0].message, 'falta config-version', 'explains the missing version');

const wrongVersion = archiveOf([entry('config/suite/config.yml', 'a: 1\nconfig-version: 2.0\n')]);
const wrongCheck = Mapping.validateSuite(wrongVersion, { opaque: [] });
assertEq(wrongCheck.errors.length, 1, 'rejects a wrong config-version');
assertEq(wrongCheck.errors[0].message, 'config-version no es 1.0', 'explains the wrong version');

const brokenYaml = archiveOf([entry('config/suite/config.yml', 'a: [1, 2\nconfig-version: 1.0\n')]);
const brokenCheck = Mapping.validateSuite(brokenYaml, { opaque: [] });
assertEq(brokenCheck.errors.length, 1, 'rejects invalid YAML');
assert(brokenCheck.errors[0].message.startsWith('YAML inválido'), 'explains the invalid YAML');

const spelRules = archiveOf([entry('config/iflow-firewall/rules.yml', '- id: drop\n  target: DROP\n')]);
const spelCheck = Mapping.validateSuite(spelRules, {
  opaque: ['config/iflow-firewall/rules.yml'],
});
assertEq(spelCheck.errors, [], 'an opaque file is not an error');
assertEq(spelCheck.warnings.length, 1, 'an opaque file is a warning');
assertEq(spelCheck.warnings[0].message, 'archivo opaco preservado; no se valida', 'explains the opaque file');

const arrayRoot = archiveOf([entry('config/suite/config.yml', '- a\n- b\n')]);
const arrayCheck = Mapping.validateSuite(arrayRoot, { opaque: [] });
assertEq(arrayCheck.errors.length, 1, 'rejects a non-map root');
assertEq(arrayCheck.errors[0].message, 'la raíz no es un mapa', 'explains the non-map root');

const unknownModule = archiveOf([
  entry('config/extension-x/config.yml', 'a: 1\nconfig-version: 1.0\n'),
  entry('config/extension-x/other.yml', 'b: 1\nconfig-version: 1.0\n'),
]);
const unknownCheck = Mapping.validateSuite(unknownModule, { opaque: [] });
assertEq(unknownCheck.errors, [], 'an unknown module is not an error');
assertEq(unknownCheck.warnings.length, 1, 'an unknown module warns once');
assertEq(unknownCheck.warnings[0].path, 'config/extension-x/', 'points at the module');
assertEq(unknownCheck.warnings[0].message, 'módulo desconocido', 'explains the unknown module');

const binaryYaml = archiveOf([entry('config/suite/config.yml', new Uint8Array([0xff, 0xfe, 0x00]))]);
const binaryCheck = Mapping.validateSuite(binaryYaml, { opaque: [] });
assertEq(binaryCheck.errors.length, 1, 'rejects a non-UTF-8 YAML');
assertEq(binaryCheck.errors[0].message, 'no es texto UTF-8', 'explains the encoding');

assertEq(Mapping.validateSuite(null, null).errors, [], 'tolerates an absent archive');

console.log('\n' + passed + ' passed, ' + failed + ' failed');
process.exit(failed ? 1 : 0);

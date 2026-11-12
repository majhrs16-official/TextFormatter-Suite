#!/usr/bin/env node
/* tests/unit/suiteMapping.test.cjs — unit tests for Suite.suiteMapping */

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const dir = path.join(__dirname, '..', '..');
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
  runScripts: 'outside-only',
  url: 'https://localhost/',
});
const { window } = dom;
window.TextEncoder = globalThis.TextEncoder;
window.TextDecoder = globalThis.TextDecoder;
for (const code of ['yaml.js', 'zip.js', 'suiteArchive.js', 'model.js', 'suiteMapping.js']) {
  window.eval(fs.readFileSync(path.join(dir, 'js', code), 'utf8'));
}
const Suite = window.Suite;
const Mapping = Suite.suiteMapping;
const Archive = Suite.suiteArchive;

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
  entry('config/iflow-vm/policy.flow', new Uint8Array([0, 1, 2, 3])),
  entry('manager/library/status', 'suite-x 1.0.0\n'),
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
assert(imported.consumed.includes('config/suite/config.yml'), 'marks config as consumed');
assert(imported.deferred.includes('config/iflow-vm/policy.flow'), 'defers the VM ROM');
assert(imported.deferred.includes('manager/library/status'), 'defers the package database');
assert(imported.opaque.includes('config/iflow-vm/policy.flow') === false, 'does not mark deferred files as opaque');

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

console.log('\n--- toSuite: untouched files preserve bytes ---');
const originalConfig = text(source, 'config/suite/config.yml');
const originalChannel = text(source, 'config/textformatter/channels/chat.global.yml');
const originalDiscord = text(source, 'config/sync-discord/config.yml');
const originalMessages = text(source, 'config/suite/messages.yml');
const untouched = Mapping.toSuite(imported.state, source, imported);
assertEq(untouched.written, [], 'writes nothing when nothing changed');
assert(untouched.preserved.includes('config/suite/config.yml'), 'preserves the untouched config');
bytesEq(source.get('config/suite/config.yml'), encoder.encode(originalConfig), 'config bytes are identical');
bytesEq(
  source.get('config/textformatter/channels/chat.global.yml'),
  encoder.encode(originalChannel),
  'channel bytes are identical'
);
bytesEq(source.get('config/sync-discord/config.yml'), encoder.encode(originalDiscord), 'sync bytes are identical');
bytesEq(source.get('config/suite/messages.yml'), encoder.encode(originalMessages), 'extra bytes are identical');
assertEq(text(source, 'config/iflow-vm/policy.flow'), '\u0000\u0001\u0002\u0003', 'deferred ROM bytes are identical');

console.log('\n--- toSuite: edited files are serialized ---');
imported.state.config['quick-look'] = true;
const edited = Mapping.toSuite(imported.state, source, imported);
assert(edited.written.includes('config/suite/config.yml'), 'rewrites the edited config');
const editedConfig = text(source, 'config/suite/config.yml');
assert(editedConfig.includes('quick-look: true'), 'writes the new config value');
assert(editedConfig.trimEnd().endsWith('config-version: 1.0'), 'adds config-version at the end');
assert(!editedConfig.includes('# comentario'), 'does not invent comments');

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

console.log('\n' + passed + ' passed, ' + failed + ' failed');
process.exit(failed ? 1 : 0);

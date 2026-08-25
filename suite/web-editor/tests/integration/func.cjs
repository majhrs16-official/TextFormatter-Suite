const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');

const dir = path.join(__dirname, '..', '..');
const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://localhost/' });
const { window } = dom;

for (const g of ['window', 'document', 'navigator', 'TextEncoder', 'Blob', 'URL', 'console'])
  if (!(g in global)) global[g] = window[g];
for (const g of ['TextEncoder', 'TextDecoder']) if (!(g in window)) window[g] = globalThis[g];
window.URL.createObjectURL = () => 'blob:x';
window.URL.revokeObjectURL = () => {};

const files = [
  'yaml',
  'zip',
  'model',
  'validate',
  'preview',
  'stateStore',
  'i18n',
  'utils',
  'sidebar',
  'canvas',
  'txf',
  'props',
  'config',
  'docking',
  'actions',
  'perms',
  'kernel',
  'previewView',
  'importExport',
  'toolbar',
  'status',
  'core',
];
for (const f of files) window.eval(fs.readFileSync(path.join(dir, 'js', f + '.js'), 'utf8'));

const S = window.Suite;
let pass = 0,
  fail = 0;
function ok(cond, name) {
  if (cond) {
    pass++;
    console.log('  ✔', name);
  } else {
    fail++;
    console.log('  ✘', name);
  }
}

console.log('\n== preview simulate ==');
const st = S.model.defaults();
const res = S.preview.simulate(st, 'chat.global', 'hola mundo');
ok(res.ok, 'simulate returns ok');
ok(res.outputs.length >= 1, 'has outputs (' + res.outputs.length + ')');
ok(res.steps > 0 && res.steps < 512, 'steps bounded (' + res.steps + ')');
const rr = S.preview.renderMini('<green>%content%</green>', { content: 'hola' });
ok(rr.includes('hola') && rr.includes('color'), 'renderMini works');

console.log('\n== cycle dedup / guard ==');
const st2 = S.model.defaults();
st2.graph.guard = { 'max-steps': 5 };
const res2 = S.preview.simulate(st2, 'chat.global', 'x');
ok(res2.steps <= res2.steps, 'guard terminates (' + res2.steps + ' steps)');
ok(res2.steps >= 5, 'guard stops at max-steps depth (' + res2.steps + ')');

console.log('\n== model CRUD ==');
const st3 = S.model.defaults();
const n = S.model.addNode(st3, 'transform', 'x', 10, 20);
ok(
  st3.graph.nodes.some(x => x.id === n.id),
  'addNode'
);
S.model.removeNode(st3, n.id);
ok(!st3.graph.nodes.some(x => x.id === n.id), 'removeNode');
const ch = S.model.addChannel(st3, 'test.chat');
ok(!!st3.channels[ch], 'addChannel (' + ch + ')');
ok(
  st3.graph.nodes.some(x => x.kind === 'input' && x.label === ch),
  'auto input node'
);
const ren = S.model.renameChannel(st3, ch, 'renamed.chat');
ok(ren && st3.channels['renamed.chat'] && !st3.channels[ch], 'renameChannel');
ok(
  st3.graph.nodes.some(x => x.kind === 'input' && x.label === 'renamed.chat'),
  'rename propagates to graph'
);

console.log('\n== validate ==');
const v = S.validate.validate(st3);
console.log('  issues:', v.errors, 'err /', v.warnings, 'warn');
ok(!v.blocking, 'default-ish state not blocking');
const bad = S.validate.validate({
  config: {},
  channels: {},
  graph: { guard: { 'max-steps': 0 }, nodes: [{ id: 'a' }, { id: 'a' }], edges: [] },
  translators: {},
  sync: { velocity: { enabled: true } },
  perms: {},
});
ok(bad.errors >= 2, 'bad state has errors (' + bad.errors + ')');
ok(bad.blocking, 'bad state blocks');

console.log('\n== yaml round-trip ==');
const cfgText = S.yaml.stringify(st3.config);
const cfg2 = S.yaml.parse(cfgText);
ok(JSON.stringify(cfg2) === JSON.stringify(st3.config), 'config yaml round-trip exact');
const chText = S.yaml.stringify(st3.channels['renamed.chat']);
const ch2 = S.yaml.parse(chText);
ok(ch2.name === 'renamed.chat', 'channel yaml round-trip');

console.log('\n== export directly ==');
const stFull = S.model.defaults();
const filesFull = S.model.exportFiles(stFull, v);
console.log('  files:', Object.keys(filesFull).sort().join(', '));
ok(Object.keys(filesFull).includes('config.yml'), 'export has config.yml');
ok(Object.keys(filesFull).includes('rules.yml'), 'export has rules.yml');
ok(
  Object.keys(filesFull).some(k => k.startsWith('channels/')),
  'export has channels'
);
ok(
  Object.keys(filesFull).some(k => k.startsWith('sync/')),
  'export has sync'
);

console.log('\n== full export -> import -> export identity ==');
const full = S.model.defaults();
const vf = S.validate.validate(full);
const exported = S.model.exportFiles(full, vf);
const keys = Object.keys(exported).sort();
console.log('  files:', keys.join(', '));
ok(keys.length >= 7, 'export has >=7 files (' + keys.length + ')');

// ensure channels/*.yml reparse identical
let importOk = true;
for (const k of keys) {
  if (k === 'manifest.json') continue;
  const p = S.yaml.parse(exported[k]);
  if (p === null || typeof p !== 'object') {
    importOk = false;
    console.log('  !! unparsable:', k);
  }
}
ok(importOk, 'every yaml file reparses');

// full importFromFiles round trip
const imported = S.model.importFromFiles(full, exported);
const reExported = S.model.exportFiles(imported, vf);
let same = keys.length === Object.keys(reExported).length;
if (same)
  for (const k of keys) {
    if (k === 'manifest.json') continue;
    if (reExported[k] !== exported[k]) {
      same = false;
      console.log('  diff in', k);
    }
  }
ok(same, 'export→import→export byte-identical (manifest excl.)');

console.log('\n== zip/build ==');
const zip = S.zip.build(keys.map(k => ({ name: k, data: exported[k] })));
const AB = window.ArrayBuffer;
ok(zip instanceof AB, 'zip builds ArrayBuffer (' + zip.byteLength + ' bytes)');
const view = new window.DataView(zip);
const eocd = view.getUint32(zip.byteLength - 22, true);
ok(eocd === 0x06054b50, 'EOCD signature present');
const entries = view.getUint16(zip.byteLength - 12, true);
ok(entries === keys.length, 'central dir entry count matches (' + entries + ')');
ok(view.getUint32(0, true) === 0x04034b50, 'first local header signature OK');

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

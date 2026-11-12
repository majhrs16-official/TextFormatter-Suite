const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { installFetch } = require('./_fetch.cjs');

const dir = path.join(__dirname, '..', '..');
const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://localhost/' });
const { window } = dom;
installFetch(window, dir);
for (const g of ['window', 'document', 'navigator', 'TextEncoder', 'Blob', 'URL', 'console'])
  if (!(g in global)) global[g] = window[g];
for (const g of ['TextEncoder', 'TextDecoder']) if (!(g in window)) window[g] = globalThis[g];
window.URL.createObjectURL = () => 'blob:x';
window.URL.revokeObjectURL = () => {};
window.Error = Error;

const errors = [];
window.addEventListener('error', e => errors.push(e.message));
const files = [
  'yaml',
  'yamlCst',
  'packageDb',
  'rom',
  'zip',
  'suiteArchive',
  'model',
  'suiteMapping',
  'validate',
  'preview',
  'stateStore',
  'i18n',
  'utils',
  'sidebar',
  'canvas',
  'txf',
  'props',
  'paths',
  'config',
  'docking',
  'actions',
  'perms',
  'kernel',
  'previewView',
  'importExport',
  'packages',
  'roms',
  'toolbar',
  'status',
  'core',
];
for (const f of files) window.eval(fs.readFileSync(path.join(dir, 'js', f + '.js'), 'utf8'));
window.document.dispatchEvent(new window.Event('DOMContentLoaded'));

function hashOf(bytes) {
  return crypto.createHash('sha256').update(Buffer.from(bytes)).digest();
}

function buildFlow(code) {
  const body = Buffer.from(code || [0x00, 0x00]);
  const caps = Buffer.from([0x6c, 0x6f, 0x67, 0x00]);
  const out = Buffer.alloc(20 + body.length + caps.length);
  out.writeUInt32BE(0x464c4f57, 0);
  out[4] = 2;
  out[5] = 2;
  out[6] = 2;
  out[7] = 1;
  out.writeUInt16BE(0, 8);
  out.writeUInt16BE(body.length, 10);
  out.writeUInt16BE(0, 12);
  out[14] = 1;
  body.copy(out, 20);
  caps.copy(out, 20 + body.length);
  return new Uint8Array(Buffer.concat([out, hashOf(out)]));
}

setTimeout(() => {
  let pass = 0;
  let fail = 0;
  function ok(condition, name) {
    if (condition) {
      pass++;
      console.log('  ✔', name);
    } else {
      fail++;
      console.log('  ✘', name);
    }
  }

  const S = window.Suite;
  const $ = S.utils.$;
  const store = window.StateStore;
  const Rom = S.rom;

  S.views.switchView('rom');
  ok(S.i18n.UI.view === 'rom', 'rom view opens');
  ok($('#romWarning').textContent.includes('runtime'), 'warning is visible');
  ok($('#romList').querySelectorAll('[data-rom-index]').length === 0, 'starts with no ROM');

  const flow = buildFlow([0x00, 0x00]);
  const addedFlow = S.views.addRomBytes(flow, 'policy.flow');
  ok(addedFlow && addedFlow.valid, 'adds a valid .flow');
  ok(store.getState().roms.length === 1, 'the flow is in the state');
  ok(store.getState().roms[0].path === 'config/iflow-vm/policy.flow', 'uses the canonical flow path');
  ok($('#romList').querySelectorAll('[data-rom-index]').length === 1, 'list shows the flow');

  const wasm = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]);
  const addedWasm = S.views.addRomBytes(wasm, 'policy.wasm');
  ok(addedWasm && addedWasm.valid, 'adds a valid .wasm');
  ok(addedWasm.sha256 === Rom.sha256Hex(wasm), 'calculates the wasm SHA-256');
  ok(addedWasm.sidecarPath === 'config/iflow-wasm/policy.sha256', 'assigns the canonical sidecar');

  const secondFlow = S.views.addRomBytes(buildFlow([0x00, 0x00, 0x00]), 'policy.flow');
  ok(secondFlow.path === 'config/iflow-vm/policy-copy.flow', 'a second flow becomes a clone');
  ok(store.getState().roms.length === 3, 'the clone is in the state');

  const invalid = S.views.addRomBytes(new Uint8Array([1, 2, 3]), 'broken.flow');
  ok(invalid === null, 'rejects an invalid .flow');
  ok(store.getState().roms.length === 3, 'an invalid .flow does not mutate the state');

  S.views.switchView('rom');
  const list = $('#romList');
  const cloneIndex = 2;
  list.querySelector('[data-rom-index="' + cloneIndex + '"]').onclick();
  ok($('#romEditor [data-rom-field="path"]').value === 'config/iflow-vm/policy-copy.flow', 'selects the clone');

  const replacement = buildFlow([0x00, 0x00, 0x00, 0x00]);
  const replaced = S.views.replaceRomBytes(replacement);
  ok(replaced && replaced.valid, 'replaces the clone bytes');
  ok(
    Rom.fromBase64(store.getState().roms[cloneIndex].data).length === replacement.length,
    'the clone keeps the new bytes'
  );

  window.prompt = () => 'config/iflow-vm/policy-copy-2.flow';
  const cloned = S.views.cloneRom();
  ok(cloned && cloned.path === 'config/iflow-vm/policy-copy-2.flow', 'clones the selected ROM');
  ok(store.getState().roms.length === 4, 'the clone is added to the state');

  S.views.switchView('rom');
  const pathInput = $('#romEditor [data-rom-field="path"]');
  pathInput.value = 'config/iflow-vm/policy-copy-3.flow';
  pathInput.dispatchEvent(new window.Event('change'));
  ok(store.getState().roms[3].path === 'config/iflow-vm/policy-copy-3.flow', 'edits the clone path');

  S.views.removeRom();
  ok(store.getState().roms.length === 3, 'removes the selected ROM');

  ok(errors.length === 0, 'no runtime errors during the ROM view');
  if (errors.length > 0) {
    console.log('errors:', errors);
  }
  console.log('\n' + pass + ' passed, ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
}, 300);

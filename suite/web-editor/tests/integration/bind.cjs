const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '..', '..');
const dom = new JSDOM(fs.readFileSync(path.join(dir, 'index.html'), 'utf8'), {
  runScripts: 'outside-only',
  url: 'https://localhost/',
});
const { window } = dom;
for (const g of [
  'window',
  'document',
  'localStorage',
  'navigator',
  'TextEncoder',
  'Blob',
  'URL',
  'console',
  'requestAnimationFrame',
  'cancelAnimationFrame',
])
  if (!(g in global)) global[g] = window[g];
for (const g of ['TextEncoder', 'TextDecoder']) if (!(g in window)) window[g] = globalThis[g];
const errors = [];
window.addEventListener('error', e => errors.push(e.message));
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
  'paths',
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
if (!window.StateStore.getState()) window.StateStore.init(window.Suite.model.defaults());
window.document.dispatchEvent(new window.Event('DOMContentLoaded'));

function getPath(o, p) {
  return p.split('.').reduce((a, k) => a && a[k], o);
}

setTimeout(() => {
  const out = [];
  const st = window.StateStore.getState();
  const switches = window.document.querySelectorAll('.switch[data-bind]');
  out.push('switches totales: ' + switches.length);
  // verify each data-bind resolves to an existing boolean in state
  let missing = [];
  for (const sw of switches) {
    const v = getPath(st, sw.dataset.bind);
    if (typeof v !== 'boolean') missing.push(sw.dataset.bind + '=' + JSON.stringify(v));
  }
  out.push('paths inválidos: ' + (missing.length ? missing.join(', ') : 'NONE'));

  // initial on-state must match state
  let mismatch = 0;
  for (const sw of switches) {
    const v = getPath(st, sw.dataset.bind);
    if (!!sw.classList.contains('on') !== v) mismatch++;
  }
  out.push('switches que no reflejan estado inicial: ' + mismatch);

  // toggle each switch via click and verify state + DOM update
  const config = window.document.querySelector('.viewport[data-canvas="config"]');
  // bindConfigSelects runs during switchView(config); ensure bound
  window.document
    .querySelector('#sidebar [data-view="config"]')
    .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const checked = [];
  // Consultar el DOM vivo en cada iteración: los switches dinámicos se
  // re-renderizan tras cada toggle y las referencias cacheadas quedan huérfanas.
  const total = window.document.querySelectorAll('.switch[data-bind]').length;
  for (let i = 0; i < total; i++) {
    const sw = window.document.querySelectorAll('.switch[data-bind]')[i];
    if (!window.document.contains(sw)) {
      continue;
    }
    const before = getPath(window.StateStore.getState(), sw.dataset.bind);
    sw.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const after = getPath(window.StateStore.getState(), sw.dataset.bind);
    if (after !== !before) checked.push(sw.dataset.bind + ': no toggló (' + before + '->' + after + ')');
  }
  out.push('toggle fallidos: ' + (checked.length ? checked.join(', ') : 'NONE'));
  out.push('canUndo tras toggles: ' + window.StateStore.canUndo());

  console.log(out.join('\n'));
  console.log('ERRORS (' + errors.length + '): ' + errors.join(' | '));
  process.exit(errors.length || missing.length || checked.length || mismatch ? 1 : 0);
}, 200);

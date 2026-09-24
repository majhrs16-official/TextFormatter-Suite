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
window.Error = Error;

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
window.document.dispatchEvent(new window.Event('DOMContentLoaded'));

setTimeout(() => {
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

  // click first iflow node on the canvas
  const node = window.document.querySelector('.node');
  ok(node != null, 'canvas rendered at least one node');
  if (node) {
    node.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const pfId = window.document.getElementById('pfId');
    ok(
      pfId != null && pfId.value.length > 0,
      'props panel filled after node click (pfId=' + (pfId ? pfId.value : 'null') + ')'
    );
  }

  // click first sidebar channel entry
  const chanItem = window.document.querySelector('li[data-channel]');
  ok(chanItem != null, 'sidebar lists channels');
  if (chanItem) {
    chanItem.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    ok(window.Suite.i18n.UI.sel != null, 'selection stored after channel click');
  }

  console.log('errors:', errors.length ? errors : 'none');
  ok(errors.length === 0, 'no runtime errors during clicks');
  console.log(pass + ' passed, ' + fail + ' failed');
  process.exit(fail || errors.length ? 1 : 0);
}, 200);

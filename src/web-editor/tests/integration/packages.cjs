const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');
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
  'disasm',
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

  S.views.switchView('packages');
  ok(S.i18n.UI.view === 'packages', 'packages view opens');
  ok($('#pkgWarning').textContent.includes('source of truth'), 'warning is visible');
  ok($('#pkgWarning').textContent.includes('no package database'), 'missing database is explained');
  ok($('#pkgList').querySelectorAll('[data-pkg-index]').length === 0, 'starts with no packages');

  $('#pkgAdd').onclick();
  const added = store.getState().packages;
  ok(added.length === 1, 'add creates a package');
  ok($('#pkgList').querySelectorAll('[data-pkg-index]').length === 1, 'list shows the package');
  ok(!!$('#pkgEditor [data-pkg-field="name"]'), 'editor exposes the package fields');

  const name = $('#pkgEditor [data-pkg-field="name"]');
  name.value = 'iflow';
  name.dispatchEvent(new window.Event('change'));
  ok(store.getState().packages[0].name === 'iflow', 'editing the name updates the state');

  const deps = $('#pkgEditor [data-pkg-field="dependencies"]');
  deps.value = 'iflow-16@>=1.0.0, textformatter';
  deps.dispatchEvent(new window.Event('change'));
  ok(
    JSON.stringify(store.getState().packages[0].dependencies) === JSON.stringify(['iflow-16@>=1.0.0', 'textformatter']),
    'dependencies are normalized'
  );

  const size = $('#pkgEditor [data-pkg-field="size"]');
  size.value = '-1';
  size.dispatchEvent(new window.Event('change'));
  ok(store.getState().packages[0].size === 0, 'an invalid size is rejected without mutating');

  $('#pkgRemove').onclick();
  ok(store.getState().packages.length === 0, 'remove deletes the selected package');

  ok(errors.length === 0, 'no runtime errors during the packages view');
  if (errors.length > 0) {
    console.log('errors:', errors);
  }
  console.log('\n' + pass + ' passed, ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
}, 300);

const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '..', '..');
const dom = new JSDOM(fs.readFileSync(dir + '/index.html', 'utf8'), {
  runScripts: 'outside-only',
  url: 'https://localhost/',
});
const w = dom.window;
for (const g of ['window', 'document', 'localStorage', 'navigator', 'TextEncoder', 'Blob', 'URL'])
  if (!(g in global)) global[g] = w[g];
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
for (const f of files) w.eval(fs.readFileSync(path.join(dir, 'js', f + '.js'), 'utf8'));
for (const g of ['TextEncoder', 'TextDecoder']) if (!(g in window)) window[g] = globalThis[g];
w.document.dispatchEvent(new w.Event('DOMContentLoaded'));
const errs = [];
w.addEventListener('error', e => errs.push('ERR:' + String(e.message)));

setTimeout(() => {
  const row = w.document.getElementById('chainRow');
  const chips = () => [...row.querySelectorAll('.rule-chip')].map(c => c.textContent);
  const out = [];
  out.push('txf chips before: ' + JSON.stringify(chips()));
  // switchView txf
  w.document.querySelector('#sidebar [data-view="txf"]').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
}, 200);
setTimeout(() => {
  const row = w.document.getElementById('chainRow');
  const chips = () => [...row.querySelectorAll('.rule-chip')].map(c => c.textContent);
  const out = [];
  out.push('txf chips: ' + JSON.stringify(chips()));
  out.push('chain has #chainRow = ' + !!row);
  // test drag order: grab a channel chip and "drop" it earlier via drop handler
  const dchip = row.querySelector('.rule-chip[data-channel]');
  out.push('first data-channel chip = ' + (dchip ? dchip.textContent : 'none'));
  // simulate: drag chip A onto row and reorder
  const dt = w.Suite;
  const UIw = null;
  // grab the chip with dataset.channel and simulate dragover/drop
  const srcc = row.querySelectorAll('.rule-chip[data-channel]')[2];
  if (srcc) {
    const dtObj = {
      setData: (m, v) => {
        dtObj[m] = v;
      },
      getData: m => dtObj[m] || '',
      effectAllowed: 'copy',
    };
    const se = new w.Event('dragstart', { bubbles: true, cancelable: true });
    Object.defineProperty(se, 'dataTransfer', { value: dtObj });
    srcc.dispatchEvent(se);
    srcc.classList.add('dragging');
    const before = Object.keys(w.Suite.app.state().channels).join(',');
    out.push('state channels order: ' + before);
    const de = new w.Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(de, 'dataTransfer', { value: dtObj });
    row.dispatchEvent(de);
    srcc.classList.remove('dragging');
    const de2 = new w.Event('dragend', { bubbles: true, cancelable: true, clientX: 0, clientY: 0 });
    Object.defineProperty(de2, 'dataTransfer', { value: dtObj });
    srcc.dispatchEvent(de2);
    out.push('chainOrder set = ' + JSON.stringify(w.Suite._ui ? w.Suite._ui.chainOrder : 'n/a'));
  }
  // verify re-eval: after drop, renderTxf keeps order? check UI state via chips
  const chipsAfter = [...row.querySelectorAll('.rule-chip')].map(c => c.textContent);
  out.push('txf chips after: ' + JSON.stringify(chipsAfter));
  console.log(out.join('\n'));
  console.log('ERRORS (' + errs.length + '): ' + errs.join(' | '));
  process.exit(errs.length ? 1 : 0);
}, 400);

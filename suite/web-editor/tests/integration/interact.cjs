const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '..', '..');
const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://localhost/' });
const { window } = dom;
for (const g of ['window', 'document', 'localStorage', 'navigator', 'TextEncoder', 'Blob', 'URL', 'console'])
  if (!(g in global)) global[g] = window[g];
for (const g of ['TextEncoder', 'TextDecoder']) if (!(g in window)) window[g] = globalThis[g];
window.URL.createObjectURL = () => 'blob:x';
window.URL.revokeObjectURL = () => {};
const errors = [];
window.addEventListener('error', e => errors.push(e.message));
for (const f of ['yaml', 'zip', 'model', 'validate', 'preview', 'stateStore'])
  window.eval(fs.readFileSync(path.join(dir, 'js', f + '.js'), 'utf8'));
window.document.dispatchEvent(new window.Event('DOMContentLoaded'));

function click(el) {
  if (el) el.dispatchEvent(new window.MouseEvent('click', { bubbles: true, cancelable: true }));
}
function report(label) {
  console.log(label, '->', errors.length ? errors[errors.length - 1] : 'OK');
}

setTimeout(() => {
  click(window.document.querySelector('.node'));
  report('node click');
  const segs = window.document.querySelectorAll('#sidebar [data-side]');
  click(segs[1]);
  report('sidebar seg[1] (palette)');
  const activePal = window.document.querySelector('#sidebar .pal-item');
  window.document.body.classList.contains('no');
  report('palette visible');
  const chans = window.document.querySelectorAll('#sidebar [data-channel]');
  click(chans[0]);
  report('channel item click -> renderProps');
  const cards = window.document.querySelectorAll('.viewport[data-canvas="txf"] .card');
  click(cards[0]);
  report('txf card click');
  console.log('FINAL errors:', errors.length ? JSON.stringify(errors.slice(0, 8), null, 1) : 'none');
  process.exit(errors.length ? 1 : 0);
}, 250);

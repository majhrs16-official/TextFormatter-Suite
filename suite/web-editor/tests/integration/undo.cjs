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
for (const f of ['yaml', 'zip', 'model', 'validate', 'preview', 'stateStore'])
  window.eval(fs.readFileSync(path.join(dir, 'js', f + '.js'), 'utf8'));
window.document.dispatchEvent(new window.Event('DOMContentLoaded'));
if (!window.StateStore.getState()) window.StateStore.init(window.Suite.model.defaults());
setTimeout(() => {
  const out = [];
  const initNodes = window.StateStore.getState().graph.nodes.length;
  const initLabels = window.StateStore.getState()
    .graph.nodes.map(n => n.label)
    .join(',');
  window.StateStore.mutate('add', () => {
    const s = window.StateStore.getState();
    window.Suite.model.addNode(s, 'loop', 'undo_test', 10, 10);
  });
  out.push('canUndo after add: ' + window.StateStore.canUndo());
  window.StateStore.mutate('add2', () => {
    const s = window.StateStore.getState();
    window.Suite.model.addNode(s, 'sleep', 'undo_test2', 20, 20);
  });
  out.push('nodes after 2 adds: ' + window.StateStore.getState().graph.nodes.length);
  window.StateStore.undo();
  out.push(
    'nodes after undo: ' + window.StateStore.getState().graph.nodes.length + ' (expect ' + (initNodes + 1) + ')'
  );
  out.push('canRedo after undo: ' + window.StateStore.canRedo());
  window.StateStore.redo();
  out.push(
    'nodes after redo: ' + window.StateStore.getState().graph.nodes.length + ' (expect ' + (initNodes + 2) + ')'
  );
  // undo twice back to init
  window.StateStore.undo();
  window.StateStore.undo();
  out.push(
    'labels back to init: ' +
      (window.StateStore.getState()
        .graph.nodes.map(n => n.label)
        .join(',') ===
        initLabels)
  );
  out.push('canUndo at init: ' + window.StateStore.canUndo() + ' (expect false)');
  console.log(out.join('\n'));
  console.log('ERRORS (' + errors.length + '): ' + errors.join(' | '));
  process.exit(errors.length ? 1 : 0);
}, 200);

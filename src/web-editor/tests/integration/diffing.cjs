const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '..', '..');
const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://localhost/' });
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
window.URL.createObjectURL = () => 'blob:x';
window.URL.revokeObjectURL = () => {};
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
if (!window.StateStore.getState()) window.StateStore.init(window.Suite.model.defaults());

const vp = () => window.document.querySelector('.viewport[data-canvas="iflow"]');
const nodes = () => Array.from(vp().querySelectorAll('.node'));
const st = () => window.StateStore.getState();

setTimeout(() => {
  const out = [];
  const before = nodes();
  out.push('initial nodes: ' + before.length);
  const firstRef = before[0];
  const firstId = firstRef.dataset.id;

  let newId = null;
  const okAdd = window.StateStore.mutate('test add', () => {
    const s = window.StateStore.getState();
    const node = window.Suite.model.addNode(s, 'loop', 'test_loop', 500, 500);
    newId = node.id;
  });
  out.push('add mutate ok: ' + okAdd);
  const after = nodes();
  out.push('after add nodes: ' + after.length);
  out.push('existing elements reused: ' + before.every((el, i) => after.indexOf(el) === i));
  out.push('new node present (' + newId + '): ' + !!after.find(el => el.dataset.id === newId));

  const okMove = window.StateStore.mutate('move', () => {
    const s = window.StateStore.getState();
    const t = s.graph.nodes.find(x => x.id === newId);
    t.x = 333;
    t.y = 222;
  });
  out.push('move mutate ok: ' + okMove);
  const elMoved = nodes().find(el => el.dataset.id === newId);
  out.push('moved style.left: ' + elMoved.style.left + ' (expect 333px)');
  out.push('moved style.top: ' + elMoved.style.top + ' (expect 222px)');

  const okDel = window.StateStore.mutate('del', () => {
    const s = window.StateStore.getState();
    s.graph.nodes = s.graph.nodes.filter(x => x.id !== newId);
  });
  out.push('del mutate ok: ' + okDel);
  out.push('after del nodes: ' + nodes().length);
  out.push('deleted element removed: ' + !nodes().find(el => el.dataset.id === newId));

  const okRename = window.StateStore.mutate('rename', () => {
    const s = window.StateStore.getState();
    const t = s.graph.nodes.find(x => x.id === firstId);
    t.label = 'RENAMED!';
  });
  out.push('rename mutate ok: ' + okRename);
  const firstNow = nodes().find(el => el.dataset.id === firstId);
  out.push('same element after rename: ' + (firstNow === firstRef));
  out.push('label updated: ' + firstNow.querySelector('.n-label').textContent);

  for (let i = 0; i < 20; i++) window.StateStore.mutate('noop' + i, () => {});
  out.push('nodes after 20 renders: ' + nodes().length + ' (expect 7)');
  out.push('first element still same ref: ' + (nodes().find(el => el.dataset.id === firstId) === firstRef));

  // graph integrity: edges match state, no orphans
  const sFinal = st();
  const ids = new Set(sFinal.graph.nodes.map(n => n.id));
  const orphanEdges = (sFinal.graph.edges || []).filter(e => !ids.has(e.from) || !ids.has(e.to));
  out.push('orphan edges: ' + orphanEdges.length);
  out.push('validate errors: ' + window.Suite.validate.validate(sFinal).errors);

  console.log(out.join('\n'));
  console.log('ERRORS (' + errors.length + '): ' + errors.join(' | '));
  process.exit(errors.length ? 1 : 0);
}, 200);

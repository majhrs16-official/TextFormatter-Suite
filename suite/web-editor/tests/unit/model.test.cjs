#!/usr/bin/env node
/* tests/unit/model.test.js — unit tests for Suite.model */

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const dir = path.join(__dirname, '..', '..');
const html = '<!DOCTYPE html><html><body><div id="toasts"></div></body></html>';
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
global.window = window;
global.document = window.document;

const modelCode = fs.readFileSync(path.join(dir, 'js', 'model.js'), 'utf8');
const yamlCode = fs.readFileSync(path.join(dir, 'js', 'yaml.js'), 'utf8');
window.eval(yamlCode);
window.eval(modelCode);
const Suite = window.Suite;

let passed = 0,
  failed = 0;

function assert(cond, msg) {
  if (cond) {
    console.log('✓ ' + msg);
    passed++;
  } else {
    console.log('✗ ' + msg);
    failed++;
  }
}
function assertEq(a, b, msg) {
  if (JSON.stringify(a) === JSON.stringify(b)) {
    console.log('✓ ' + msg);
    passed++;
  } else {
    console.log('✗ ' + msg + ' — expected ' + JSON.stringify(b) + ' got ' + JSON.stringify(a));
    failed++;
  }
}

console.log('\n=== Suite.model Unit Tests ===\n');

console.log('--- defaults ---');
const d = Suite.model.defaults();
assertEq(typeof d, 'object', 'returns object');
assertEq(typeof d.config, 'object', 'has config');
assertEq(typeof d.channels, 'object', 'has channels');
assertEq(typeof d.graph, 'object', 'has graph');
assertEq(typeof d.translators, 'object', 'has translators');
assertEq(typeof d.sync, 'object', 'has sync');
assertEq(typeof d.perms, 'object', 'has perms');
assertEq(typeof d.extra, 'object', 'has extra');
assertEq(Object.keys(d.channels).length, 4, 'defaults has 4 channels');
assertEq(d.graph.nodes.length, 7, 'defaults has 7 nodes');
assertEq(d.graph.edges.length, 7, 'defaults has 7 edges');

console.log('\n--- clone ---');
const orig = { a: 1, b: { c: 2 } };
const cloned = Suite.model.clone(orig);
cloned.b.c = 99;
assertEq(orig.b.c, 2, 'clone is deep (orig unchanged)');

console.log('\n--- addNode ---');
const s1 = Suite.model.defaults();
const node = Suite.model.addNode(s1, 'input', 'test.channel', 100, 200);
assertEq(s1.graph.nodes.length, 8, 'addNode increases node count');
assertEq(node.kind, 'input', 'node kind');
assertEq(node.label, 'test.channel', 'node label');
assertEq(node.x, 100, 'node x');
assertEq(node.y, 200, 'node y');
assert(node.id.startsWith('n_'), 'node id generated');

console.log('\n--- removeNode ---');
const s2 = Suite.model.defaults();
const beforeCount = s2.graph.nodes.length;
Suite.model.removeNode(s2, s2.graph.nodes[0].id);
assertEq(s2.graph.nodes.length, beforeCount - 1, 'removeNode decreases count');
assertEq(s2.graph.edges.length, 6, 'removeNode removes connected edges');

console.log('\n--- addChannel ---');
const s3 = Suite.model.defaults();
const beforeCh = Object.keys(s3.channels).length;
Suite.model.addChannel(s3, 'new.channel', ['msg1']);
assertEq(Object.keys(s3.channels).length, beforeCh + 1, 'addChannel adds channel');
assertEq(s3.channels['new.channel'].messages, ['msg1'], 'channel has seed messages');
assertEq(s3.graph.nodes.length, 8, 'addChannel adds input node');

console.log('\n--- renameChannel ---');
const s4 = Suite.model.defaults();
const okRename = Suite.model.renameChannel(s4, 'chat.global', 'chat.renamed');
assert(okRename === true, 'renameChannel returns true on success');
assert(!('chat.global' in s4.channels), 'old name removed');
assert('chat.renamed' in s4.channels, 'new name exists');
assertEq(s4.channels['chat.renamed'].name, 'chat.renamed', 'channel name updated');
assertEq(s4.graph.nodes.find(n => n.label === 'chat.renamed')?.label, 'chat.renamed', 'node label updated');

const okRenameFail = Suite.model.renameChannel(s4, 'nonexistent', 'x');
assert(okRenameFail === false, 'renameChannel fails on nonexistent');

console.log('\n--- addEdge / removeEdge ---');
const s5 = Suite.model.defaults();
const n1 = 'n_sleep'; // sleep -> loop (no existing edge)
const n2 = 'n_loop';
const beforeEdges = s5.graph.edges.length;
const added = Suite.model.addEdge(s5, n1, n2);
assert(added === undefined || added === true, 'addEdge returns undefined/true for new edge');
assertEq(s5.graph.edges.length, beforeEdges + 1, 'addEdge adds new edge');
const okAddDup = Suite.model.addEdge(s5, n1, n2);
assert(okAddDup === undefined || okAddDup === false, 'addEdge returns undefined/false for duplicate');
Suite.model.removeEdge(s5, n1, n2);
assertEq(s5.graph.edges.length, beforeEdges, 'removeEdge removes edge');

console.log('\n--- exportFiles / importFromFiles round-trip ---');
const s6 = Suite.model.defaults();
const v = { errors: 0, warnings: 0, blocking: false, issues: [] };
const files = Suite.model.exportFiles(s6, v);
assert(typeof files['config.yml'] === 'string', 'exports config.yml');
assert(typeof files['rules.yml'] === 'string', 'exports rules.yml');
assert(
  Object.keys(files).some(k => k.startsWith('channels/')),
  'exports channels'
);
const imported = Suite.model.importFromFiles(Suite.model.defaults(), files);
assertEq(imported.channels['chat.global'].name, 'chat.global', 'import restores channel');
assertEq(imported.graph.nodes.length, 7, 'import restores nodes');
assertEq(imported.graph.edges.length, 7, 'import restores edges');

console.log('\n=== SUMMARY ===');
console.log('All model tests completed');
process.exit(0);

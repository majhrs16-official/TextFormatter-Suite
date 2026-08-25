#!/usr/bin/env node
/* tests/unit/validate.test.js — unit tests for Suite.validate */

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

const yamlCode = fs.readFileSync(path.join(dir, 'js', 'yaml.js'), 'utf8');
const modelCode = fs.readFileSync(path.join(dir, 'js', 'model.js'), 'utf8');
const validateCode = fs.readFileSync(path.join(dir, 'js', 'validate.js'), 'utf8');
window.eval(require('fs').readFileSync(path.join(dir, 'js', 'yaml.js'), 'utf8'));
window.eval(require('fs').readFileSync(path.join(dir, 'js', 'model.js'), 'utf8'));
window.eval(validateCode);
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

console.log('\n=== Suite.validate Unit Tests ===\n');

function baseState() {
  return Suite.model.defaults();
}

console.log('--- valid defaults ---');
const s0 = baseState();
const v0 = Suite.validate.validate(s0);
assertEq(v0.errors, 0, 'defaults has no errors');
assertEq(v0.warnings, 1, 'defaults has 1 warning (transform node)');
assertEq(v0.blocking, false, 'defaults not blocking');

console.log('\n--- missing language ---');
const s1 = baseState();
s1.config.general.language = 'xyz-invalid';
const v1 = Suite.validate.validate(s1);
assert(v1.warnings > 0, 'warns on unknown language');
assertEq(v1.errors, 0, 'no errors on unknown language');

console.log('\n--- invalid max-steps ---');
const s2 = baseState();
s2.graph.guard['max-steps'] = 0;
let v2 = Suite.validate.validate(s2);
assert(v2.errors > 0, 'errors on max-steps 0');
assert(
  v2.issues.some(i => i.path.includes('max-steps')),
  'error path includes max-steps'
);

s2.graph.guard['max-steps'] = -5;
v2 = Suite.validate.validate(s2);
assert(v2.errors > 0, 'errors on negative max-steps');

s2.graph.guard['max-steps'] = 512;
v2 = Suite.validate.validate(s2);
assertEq(v2.errors, 0, 'valid max-steps passes');

console.log('\n--- channel validation ---');
const s3 = baseState();
s3.channels['bad:name'] = Suite.model.clone(s3.channels['chat.global']);
s3.channels['bad:name'].name = 'bad:name';
let v3 = Suite.validate.validate(s3);
assert(v3.warnings > 0, 'warns on weird channel name');

const s4 = baseState();
s4.channels['test'] = Suite.model.clone(s4.channels['chat.global']);
s4.channels['test'].permission = 'invalid permission';
let v4 = Suite.validate.validate(s4);
assert(v4.warnings > 0, 'warns on invalid permission format');

const s5 = baseState();
s5.channels['test'] = Suite.model.clone(s5.channels['chat.global']);
s5.channels['test'].messages = [];
let v5 = Suite.validate.validate(s5);
assert(v5.warnings > 0, 'warns on empty messages');

const s6 = baseState();
s6.channels['test'] = Suite.model.clone(s6.channels['chat.global']);
s6.channels['test']['rate-limit-per-second'] = -1;
let v6 = Suite.validate.validate(s6);
assert(v6.errors > 0, 'errors on negative rate-limit');

console.log('\n--- graph: duplicate node id ---');
const s7 = baseState();
const existingId = s7.graph.nodes[0].id;
s7.graph.nodes.push({ ...s7.graph.nodes[0], id: existingId });
let v7 = Suite.validate.validate(s7);
assert(v7.errors > 0, 'errors on duplicate node id');
assert(
  v7.issues.some(i => i.path.includes('nodes/' + existingId)),
  'error path includes duplicate id'
);

console.log('\n--- graph: condition without matcher ---');
const s8 = baseState();
const condNode = s8.graph.nodes.find(n => n.kind === 'cond');
condNode.matcher = {};
let v8 = Suite.validate.validate(s8);
assert(v8.warnings > 0, 'warns on condition without matcher');

console.log('\n--- graph: redirect without target ---');
const s9 = baseState();
const redirNode = s9.graph.nodes.find(n => n.kind === 'redirect');
delete redirNode.target;
let v9 = Suite.validate.validate(s9);
assert(v9.warnings > 0, 'warns on redirect without target');

console.log('\n--- graph: orphan edges ---');
const s10 = baseState();
s10.graph.edges.push({ from: 'nonexistent', to: 'n_clean' });
let v10 = Suite.validate.validate(s10);
assert(v10.errors > 0, 'errors on edge with nonexistent source');

const s11 = baseState();
s11.graph.edges.push({ from: 'n_chat.global', to: 'nonexistent' });
let v11 = Suite.validate.validate(s11);
assert(v11.errors > 0, 'errors on edge with nonexistent target');

console.log('\n--- graph: cycles without guard ---');
const s12 = baseState();
s12.graph.nodes.push({ id: 'a', kind: 'input', label: 'a', x: 0, y: 0, w: 150 });
s12.graph.nodes.push({ id: 'b', kind: 'input', label: 'b', x: 200, y: 0, w: 150 });
s12.graph.edges.push({ from: 'a', to: 'b' }, { from: 'b', to: 'a' });
let v12 = Suite.validate.validate(s12);
assert(v12.warnings > 0, 'warns on unguarded cycle');

const s13 = baseState();
// remove transform node from defaults to isolate cycle test
s13.graph.nodes = s13.graph.nodes.filter(n => n.kind !== 'transform');
s13.graph.nodes.push({ id: 'c', kind: 'cond', label: 'c', x: 0, y: 0, w: 150, matcher: { channel: 'x' } });
s13.graph.nodes.push({ id: 'd', kind: 'input', label: 'd', x: 200, y: 0, w: 150 });
s13.graph.edges.push({ from: 'c', to: 'd' }, { from: 'd', to: 'c' });
let v13 = Suite.validate.validate(s13);
assertEq(v13.warnings, 0, 'cycle with cond is ok (no warning)');

console.log('\n--- translators ---');
const s14 = baseState();
s14.translators.google.active = true;
s14.translators.libre.active = true;
let v14 = Suite.validate.validate(s14);
assert(v14.warnings > 0, 'warns on two active providers');

const s15 = baseState();
s15.translators.libre.active = true;
delete s15.translators.libre['base-url'];
let v15 = Suite.validate.validate(s15);
assert(v15.errors > 0, 'errors on active libre without base-url');

const s16 = baseState();
s16.translators.google.pool['max-concurrent'] = 0;
let v16 = Suite.validate.validate(s16);
assert(v16.errors > 0, 'errors on google pool < 1');

console.log('\n--- sync ---');
const s17 = baseState();
s17.sync.discord.enabled = true;
delete s17.sync.discord.token;
let v17 = Suite.validate.validate(s17);
assert(v17.errors > 0, 'errors on discord enabled without token');

const s18 = baseState();
s18.sync.discord.enabled = true;
s18.sync.discord.token = 'abc';
s18.sync.discord.channel = 0;
let v18 = Suite.validate.validate(s18);
assert(v18.warnings > 0, 'warns on discord channel 0');

const s19 = baseState();
s19.sync.telegram.enabled = true;
delete s19.sync.telegram.token;
let v19 = Suite.validate.validate(s19);
assert(v19.errors > 0, 'errors on telegram enabled without token');

const s20 = baseState();
s20.sync.http.enabled = true;
delete s20.sync.http['webhook-url'];
let v20 = Suite.validate.validate(s20);
assert(v20.errors > 0, 'errors on http enabled without webhook-url');

const s21 = baseState();
s21.sync['tcp-udp'].enabled = true;
s21.sync['tcp-udp'].protocol = 'INVALID';
let v21 = Suite.validate.validate(s21);
assert(v21.errors > 0, 'errors on invalid tcp-udp protocol');

console.log('\n=== SUMMARY ===');
console.log('Passed: ' + passed);
console.log('Failed: ' + failed);
process.exit(failed > 0 ? 1 : 0);

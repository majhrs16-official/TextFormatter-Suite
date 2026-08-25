#!/usr/bin/env node
/* tests/unit/stateStore.test.js — unit tests for StateStore */

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const dir = path.join(__dirname, '..', '..');
const html = '<!DOCTYPE html><html><body><div id="toasts"></div></body></html>';
const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://localhost/' });
const { window } = dom;

// Setup globals
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

// Also set global for JSDOM window
global.window = window;
global.document = window.document;

// Load stateStore in window context
const stateStoreCode = fs.readFileSync(path.join(dir, 'js', 'stateStore.js'), 'utf8');
window.eval(stateStoreCode);

const StateStore = window.StateStore;

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

console.log('\n=== StateStore Unit Tests ===\n');

// Reset stateStore between tests
function reset() {
  // Can't easily reset module state, so we test sequentially with care
}

console.log('--- init / getState ---');
StateStore.init({ a: 1, b: { c: 2 } });
const s0 = StateStore.getState();
assertEq(s0.a, 1, 'init sets state');
assertEq(s0.b.c, 2, 'init nested state');
assert(!StateStore.canUndo(), 'canUndo false after init');
assert(!StateStore.canRedo(), 'canRedo false after init');

console.log('\n--- mutate basic ---');
const ok1 = StateStore.mutate('set a', () => {
  const s = StateStore.getState();
  s.a = 2;
});
assert(ok1 === true, 'mutate returns true on success');
const s1 = StateStore.getState();
assertEq(s1.a, 2, 'mutate persists change');

console.log('\n--- history / undo / redo ---');
assert(StateStore.canUndo(), 'canUndo true after mutate');
StateStore.undo();
const s2 = StateStore.getState();
assertEq(s2.a, 1, 'undo restores previous state');
assert(StateStore.canRedo(), 'canRedo true after undo');
StateStore.redo();
const s3 = StateStore.getState();
assertEq(s3.a, 2, 'redo restores undone state');

console.log('\n--- nested mutate ---');
StateStore.mutate('nested', () => {
  const s = StateStore.getState();
  s.b.c = 3;
});
assertEq(StateStore.getState().b.c, 3, 'nested mutate works');
StateStore.undo();
assertEq(StateStore.getState().b.c, 2, 'undo nested works');

console.log('\n--- multiple mutations build history ---');
StateStore.mutate('m1', () => {
  StateStore.getState().a = 10;
});
StateStore.mutate('m2', () => {
  StateStore.getState().a = 20;
});
assertEq(StateStore.getState().a, 20, 'after m2');
StateStore.undo();
assertEq(StateStore.getState().a, 10, 'undo to m1');
StateStore.undo();
assertEq(StateStore.getState().a, 2, 'undo to initial');
StateStore.redo();
assertEq(StateStore.getState().a, 10, 'redo to m1');
StateStore.redo();
assertEq(StateStore.getState().a, 20, 'redo to m2');

console.log('\n--- validation pre (runs on state BEFORE mutate) ---');
// Pre-validator runs on state BEFORE mutation, so it sees old value
// Use post-validation for checking mutated values
StateStore.clearValidators();
StateStore.addValidator('pre', s => {
  if (s.a === 999) return 'a cannot be 999 before mutate';
});
const okPre = StateStore.mutate('pre-test', () => {
  const s = StateStore.getState();
  s.a = 999;
});
assert(okPre === true, 'first mutate to 999 succeeds (pre sees 20)');
assertEq(StateStore.getState().a, 999, 'state is 999 after first mutate');
const okPre2 = StateStore.mutate('pre-test2', () => {
  const s = StateStore.getState();
  s.a = 999;
});
assert(okPre2 === false, 'second mutate rejected by pre-validator (sees 999)');
assertEq(StateStore.getState().a, 999, 'state unchanged after rejected mutate (still 999)');
const okPre3 = StateStore.mutate('pre-test3', () => {
  const s = StateStore.getState();
  s.a = 1;
});
assert(okPre3 === false, 'third mutate also rejected (still sees 999)');
assertEq(StateStore.getState().a, 999, 'state still 999');

console.log('\n--- validation post ---');
StateStore.clearValidators();
StateStore.addValidator('post', s => {
  if (s.a > 100) return 'a too large';
});
const okLarge = StateStore.mutate('large', () => {
  const s = StateStore.getState();
  s.a = 200;
});
assert(okLarge === false, 'post-validation rejects too large');
assertEq(StateStore.getState().a, 999, 'state rolled back to 999 after post-validation fail');

console.log('\n--- replace ---');
StateStore.replace({ x: 1, y: 2 });
assertEq(StateStore.getState(), { x: 1, y: 2 }, 'replace sets new state');
assert(!StateStore.canUndo(), 'replace clears history (canUndo false)');
assert(!StateStore.canRedo(), 'replace clears history (canRedo false)');

console.log('\n--- revision counter ---');
const rev0 = StateStore.revision();
StateStore.mutate('inc', () => {
  StateStore.getState().x = 99;
});
assertEq(StateStore.revision(), rev0 + 1, 'revision increments on mutate');
StateStore.undo();
assertEq(StateStore.revision(), rev0 + 2, 'revision increments on undo');
StateStore.redo();
assertEq(StateStore.revision(), rev0 + 3, 'revision increments on redo');
StateStore.replace({ z: 1 });
assertEq(StateStore.revision(), rev0 + 4, 'revision increments on replace');

console.log('\n--- save/load localStorage ---');
StateStore.replace({ saved: true, val: 42 });
StateStore.save();
const raw = localStorage.getItem('suite-editor-project-v1');
assert(raw !== null, 'localStorage written');
StateStore.replace({ val: 0 });
const loaded = StateStore.load();
assert(loaded === true, 'load returns true');
assertEq(StateStore.getState().saved, true, 'load restores state');
assertEq(StateStore.getState().val, 42, 'load restores nested');

console.log('\n--- subscribers / notify ---');
let notified = 0;
let lastSnap = null;
const unsub = StateStore.subscribe(snap => {
  notified++;
  lastSnap = snap;
});
StateStore.mutate('sub', () => {
  const s = StateStore.getState();
  s.test = 123;
});
assertEq(notified, 1, 'subscriber notified on mutate');
assertEq(lastSnap.test, 123, 'subscriber receives snapshot');
StateStore.mutate('sub2', () => {
  const s = StateStore.getState();
  s.test = 456;
});
assertEq(notified, 2, 'subscriber notified again');
unsub();
StateStore.mutate('sub3', () => {
  const s = StateStore.getState();
  s.test = 789;
});
assertEq(notified, 2, 'unsubscribed - no notification');

console.log('\n=== SUMMARY ===');
console.log('Passed: ' + passed);
console.log('Failed: ' + failed);
process.exit(failed > 0 ? 1 : 0);

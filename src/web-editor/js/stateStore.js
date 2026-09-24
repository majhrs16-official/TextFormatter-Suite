/* stateStore.js — encapsula state, history, mutaciones y persistencia.
 * API:
 *   StateStore.init(defaultState)
 *   StateStore.getState()
 *   StateStore.mutate(label, fn, { validatePre, validatePost })
 *   StateStore.undo()
 *   StateStore.redo()
 *   StateStore.canUndo() / canRedo()
 *   StateStore.subscribe(listener) -> returns unsubscribe fn
 *   StateStore.save() / load()
 */
(function (global) {
  'use strict';

  const StoreKey = 'suite-editor-project-v1';
  const MAX_HISTORY = 80;

  let state = null;
  let history = [];
  let histIdx = -1;
  const subscribers = [];
  const validators = { pre: [], post: [] };
  let working = null;
  let rev = 0;

  function clone(v) {
    return v === undefined ? undefined : JSON.parse(JSON.stringify(v));
  }
  function deepMerge(base, over) {
    if (over === null || typeof over !== 'object' || Array.isArray(over)) {
      return over;
    }
    const out = clone(base || {});
    for (const k of Object.keys(over)) {
      if (k === '__proto__' || k === 'constructor') {
        continue;
      }
      const b = out[k],
        o = over[k];
      if (
        o !== null &&
        typeof o === 'object' &&
        !Array.isArray(o) &&
        b !== null &&
        typeof b === 'object' &&
        !Array.isArray(b)
      ) {
        out[k] = deepMerge(b, o);
      } else {
        out[k] = o;
      }
    }
    return out;
  }

  function notify(changedPaths) {
    const snapshot = clone(state);
    subscribers.forEach(fn => {
      try {
        fn(snapshot, changedPaths);
      } catch (e) {
        console.error('subscriber error', e);
      }
    });
  }
  function save() {
    try {
      localStorage.setItem(StoreKey, JSON.stringify({ state: clone(state) }));
    } catch (e) {
      console.warn('autosave', e);
    }
  }
  const scheduleSave = (() => {
    let h;
    return () => {
      clearTimeout(h);
      h = setTimeout(save, 400);
    };
  })();

  function runValidators(list, snapshot) {
    for (const v of list) {
      const err = v(snapshot);
      if (err) {
        throw new Error('Validation failed: ' + err);
      }
    }
  }

  function diffPaths(a, b, prefix = '') {
    const paths = new Set();
    const allKeys = new Set([...Object.keys(a), ...Object.keys(b)]);
    for (const k of allKeys) {
      const pa = a[k],
        pb = b[k];
      const p = prefix ? prefix + '.' + k : k;
      if (pa === undefined || pb === undefined || pa === pb) {
        if (pa !== pb) {
          paths.add(p);
        }
      } else if (typeof pa === 'object' && pa !== null && typeof pb === 'object' && pb !== null) {
        for (const sub of diffPaths(pa, pb, p)) {
          paths.add(sub);
        }
      } else if (pa !== pb) {
        paths.add(p);
      }
    }
    return paths;
  }

  const StateStore = {
    init(defaultState) {
      try {
        const raw = localStorage.getItem('suite-editor-project-v1');
        if (raw) {
          const saved = JSON.parse(raw);
          if (saved && saved.state) {
            state = saved.state;
          }
        }
      } catch (e) {
        /* ignore */
      }
      if (!state) {
        state = clone(defaultState);
      }
      history = [{ s: clone(state), label: 'init' }];
      histIdx = 0;
      rev++;
      notify();
    },

    getState() {
      return working ? state : clone(state);
    },
    revision() {
      return rev;
    },

    subscribe(fn) {
      subscribers.push(fn);
      return () => {
        const i = subscribers.indexOf(fn);
        if (i >= 0) {
          subscribers.splice(i, 1);
        }
      };
    },

    addValidator(type, fn) {
      validators[type].push(fn);
    },
    clearValidators() {
      validators.pre = [];
      validators.post = [];
    },

    mutate(label, fn, { validatePre, validatePost } = {}) {
      const pre = validatePre ? [validatePre] : validators.pre;
      const post = validatePost ? [validatePost] : validators.post;

      const snapshotBefore = clone(state);
      try {
        runValidators(pre, snapshotBefore);
      } catch (e) {
        console.warn('Pre-validation failed:', e.message);
        return false;
      }

      const prevWorking = working;
      working = state;
      try {
        fn();
      } finally {
        working = prevWorking;
      }

      const snapshotAfter = clone(state);
      try {
        runValidators(post, snapshotAfter);
      } catch (e) {
        console.warn('Post-validation failed, rolling back:', e.message);
        state = snapshotBefore;
        return false;
      }

      const changedPaths = Array.from(diffPaths(snapshotBefore, snapshotAfter));

      history = history.slice(0, histIdx + 1);
      history.push({ s: snapshotAfter, label });
      if (history.length > MAX_HISTORY) {
        history.shift();
      }
      histIdx = history.length - 1;

      scheduleSave();
      rev++;
      notify(changedPaths);
      return true;
    },

    undo() {
      if (histIdx > 0) {
        histIdx--;
        state = clone(history[histIdx].s);
        rev++;
        notify(['*']); // full revalidation on undo/redo
        return true;
      }
      return false;
    },

    redo() {
      if (histIdx < history.length - 1) {
        histIdx++;
        state = clone(history[histIdx].s);
        rev++;
        notify(['*']); // full revalidation on undo/redo
        return true;
      }
      return false;
    },

    canUndo() {
      return histIdx > 0;
    },
    canRedo() {
      return histIdx < history.length - 1;
    },

    save() {
      save();
    },
    replace(newState) {
      state = clone(newState);
      history = [{ s: clone(state), label: 'replace' }];
      histIdx = 0;
      rev++;
      scheduleSave();
      notify();
      return true;
    },
    load() {
      try {
        const raw = localStorage.getItem('suite-editor-project-v1');
        if (raw) {
          const saved = JSON.parse(raw);
          if (saved && saved.state) {
            state = saved.state;
            history = [{ s: clone(state), label: 'load' }];
            histIdx = 0;
            rev++;
            notify();
            return true;
          }
        }
      } catch (e) {
        /* ignore */
      }
      return false;
    },

    // para debugging
    __history: () =>
      history.map(h => ({
        label: h.label,
        nodes: h.s.graph?.nodes?.length,
        channels: Object.keys(h.s.channels || {}).length,
      })),
  };

  global.StateStore = StateStore;
  global.Suite = global.Suite || {};
  global.Suite.StateStore = StateStore;
})(window || this);

/* status.js — status bar cacheado por revision */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;

  let statusCache = null;

  function renderStatus(changedPaths) {
    const st = StateStore.getState();
    const rev = StateStore.revision();
    if (!statusCache || statusCache.rev !== rev) {
      const v = Suite.validate.validate(st, { changedPaths });
      const files = Suite.model.exportFiles(st, v);
      let bytes = 0;
      for (const path of Object.keys(files)) {
        bytes += new TextEncoder().encode(files[path]).length;
      }
      statusCache = { rev, v, bytes };
    }
    const { v, bytes } = statusCache;
    $('#sbCells').textContent = Object.keys(st.channels).length;
    $('#sbEdges').textContent = (st.graph.edges || []).length;
    $('#sbChain').textContent = sumMsgs();
    $('#sbSize').textContent = Suite.utils.human(bytes);
    $('#sbarZoom').textContent = Math.round(Suite.i18n.UI.zoom * 100) + '%';
    $('#sbOk').hidden = v.blocking || v.errors > 0 || v.warnings > 0;
    $('#sbErr').hidden = v.errors === 0;
    $('#sbErrN').textContent = v.errors;
    $('#sbWarn').hidden = v.warnings === 0;
    $('#sbWarnN').textContent = v.warnings;
    $('#sbDirty').hidden = !StateStore.canUndo();
    return v;
  }

  function sumMsgs() {
    const st = StateStore.getState();
    let n = 0;
    for (const c of Object.values(st.channels)) {
      n += (c.messages || []).length;
    }
    return n;
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, { renderStatus, sumMsgs });
})(window || this);

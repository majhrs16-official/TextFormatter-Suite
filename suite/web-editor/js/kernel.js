/* kernel.js — vista Kernel */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;

  function renderKernel() {
    const st = StateStore.getState();
    const mods = [
      { name: 'sync-discord', v: '2.1.0', present: true, note: 'Gateway v10 + REST' },
      { name: 'sync-telegram', v: '2.1.0', present: true, note: 'sendMessage + getUpdates' },
      { name: 'sync-http', v: '2.1.0', present: true, note: 'webhook + API REST' },
      { name: 'sync-tcpudp', v: '2.1.0', present: true, note: 'raw TCP/UDP + proto' },
      { name: 'sync-velocity', v: 'F7+', present: false, note: 'interconexión entre servidores' },
      { name: 'engine DefaultRouter', v: 'F7+', present: false, note: 'knob parallel + transform' },
      { name: 'web-editor (this)', v: '2.1.0', present: true, note: 'schema v2.2 · ServiceLoader' },
    ];
    const hasTransform = st.graph.nodes.some(n => n.kind === 'transform');
    const modGrid = $('#modGrid');
    // Only update if transform presence changed
    if (modGrid.dataset.hasTransform === String(hasTransform)) {
      return;
    }
    modGrid.dataset.hasTransform = String(hasTransform);
    modGrid.innerHTML = mods
      .map(m => {
        return (
          '<div class="card"><h3><span style="background:' +
          (m.present ? 'var(--green)' : 'var(--red)') +
          '" class="gr"></span>' +
          Suite.utils.esc(m.name) +
          '<span class="live" style="color:' +
          (m.present ? 'var(--green)' : 'var(--red)') +
          '">●</span></h3>' +
          '<p style="font-size:12px;color:var(--txt2)">' +
          Suite.utils.esc(m.note) +
          '</p>' +
          (m.name === 'engine DefaultRouter' && hasTransform
            ? '<span style="font-size:11px;color:var(--amber)">⚠ Transform nodes detectados — requiere F7+</span>'
            : '') +
          '</div>'
        );
      })
      .join('');
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, { renderKernel });
})(window || this);

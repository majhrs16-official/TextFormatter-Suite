/* actions.js — duplicar/eliminar seleccionado */
(function (global) {
  'use strict';

  const Suite = global.Suite;
  const StateStore = global.StateStore;
  const UI = global.Suite.i18n.UI;

  function dupSelected() {
    const sel = UI.sel;
    if (!sel) {
      return;
    }
    if (sel.type === 'channel') {
      const c = StateStore.getState().channels[sel.key];
      if (!c) {
        return;
      }
      StateStore.mutate('dup channel', () => {
        const st = StateStore.getState();
        let name = sel.key + '-copy';
        let i = 2;
        while (st.channels[name]) {
          name = sel.key + '-copy' + i++;
        }
        st.channels[name] = Suite.model.clone(c);
        st.channels[name].name = name;
        Suite.model.addNode(st, 'input', name, 140 + Math.random() * 160, 120 + Math.random() * 140);
      });
      Suite.utils.toast(global.Suite.i18n.t('toast_dup'), 'ok');
      Suite.views.renderSidebar();
      Suite.views.renderTxf();
      Suite.views.renderStatus();
    } else if (sel.type === 'node') {
      const n = StateStore.getState().graph.nodes.find(x => x.id === sel.id);
      if (!n) {
        return;
      }
      StateStore.mutate('dup node', () => {
        const st = StateStore.getState();
        const copy = Suite.model.clone(n);
        copy.id = n.id + '_copy';
        let i = 2;
        while (st.graph.nodes.find(x => x.id === copy.id)) {
          copy.id = n.id + '_copy' + i++;
        }
        copy.x = n.x + 60;
        copy.y = n.y + 60;
        st.graph.nodes.push(copy);
      });
      Suite.utils.toast(global.Suite.i18n.t('toast_dup'), 'ok');
      Suite.views.renderCanvas('iflow');
      Suite.views.renderStatus();
    }
  }

  function delSelected() {
    const sel = UI.sel;
    if (!sel) {
      return;
    }
    if (sel.type === 'channel') {
      StateStore.mutate('del channel', () => {
        const st = StateStore.getState();
        const victims = new Set(st.graph.nodes.filter(x => x.kind === 'input' && x.label === sel.key).map(x => x.id));
        delete st.channels[sel.key];
        st.graph.nodes = st.graph.nodes.filter(x => !victims.has(x.id));
        st.graph.edges = (st.graph.edges || []).filter(e => !victims.has(e.from) && !victims.has(e.to));
      });
      UI.sel = null;
      Suite.utils.toast(global.Suite.i18n.t('toast_del'), 'ok');
      Suite.views.renderSidebar();
      Suite.views.renderTxf();
      Suite.views.renderStatus();
    } else if (sel.type === 'node') {
      StateStore.mutate('del node', () => Suite.model.removeNode(StateStore.getState(), sel.id));
      UI.sel = null;
      Suite.utils.toast(global.Suite.i18n.t('toast_del'), 'ok');
      Suite.views.renderCanvas('iflow');
      Suite.views.renderStatus();
    } else if (sel.type === 'edge') {
      StateStore.mutate('del edge', () => Suite.model.removeEdge(StateStore.getState(), sel.from, sel.to));
      UI.sel = null;
      Suite.utils.toast(global.Suite.i18n.t('toast_del'), 'ok');
      Suite.views.renderCanvas('iflow');
      Suite.views.renderStatus();
    }
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, { dupSelected, delSelected });
})(window || this);

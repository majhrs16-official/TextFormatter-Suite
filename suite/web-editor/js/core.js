/* core.js — boot, changed, renderAll, view switching (entry point) */
(function (global) {
  'use strict';

  const Suite = global.Suite;
  const StateStore = global.StateStore;
  const UI = global.Suite.i18n.UI;

  // view switching
  function switchView(v) {
    UI.view = v;
    document.querySelectorAll('.view').forEach(s => s.classList.toggle('active', s.dataset.view === v));
    const tb2 = Suite.views.Dock?.toolbar2 || { docked: true };
    document.querySelector('#toolbar2').style.display = v === 'iflow' || v === 'txf' || !tb2.docked ? '' : 'none';
    Suite.views.renderSidebar();
    Suite.views.renderPalette();
    if (v === 'iflow') {
      Suite.views.renderCanvas('iflow');
    }
    if (v === 'txf') {
      Suite.views.renderTxf();
    }
    if (v === 'sync') {
      UI.sel = null;
    }
    Suite.views.buildCrumbs();
  }

  // breadcrumbs
  function buildCrumbs() {
    const c = document.querySelector('#crumbs');
    if (!c) {
      return;
    }
    const map = {
      iflow: 'iFlow',
      txf: 'TXF Chain',
      config: 'Config',
      translators: 'Translators',
      sync: 'Sync',
      perm: 'Perms',
      kernel: 'Kernel',
      preview: 'Preview',
    };
    c.textContent = map[UI.view] || UI.view;
  }

  // afterChange pipeline
  Suite.StateStore.subscribe(changed);
  function changed(state, changedPaths) {
    const $ = Suite.utils.$;
    $('#undoBtn').disabled = !StateStore.canUndo();
    $('#redoBtn').disabled = !StateStore.canRedo();
    Suite.views.renderStatus(changedPaths);
    if (UI.view === 'iflow') {
      Suite.views.renderCanvas('iflow');
      Suite.views.renderProps();
    } else if (UI.view === 'txf') {
      Suite.views.renderTxf();
    }
    Suite.views.renderSidebar();
    Suite.views.renderPreviewChannels();
  }

  function renderAll() {
    Suite.views.renderSidebar();
    Suite.views.renderTxf();
    Suite.views.renderProps();
    Suite.views.renderStatus();
    Suite.views.renderPreviewChannels();
    Suite.views.renderPerms();
    Suite.views.renderKernel();
    Suite.views.renderConfigValues();
    Suite.views.buildCrumbs();
    if (UI.view === 'iflow') {
      Suite.views.renderCanvas('iflow');
    } else if (UI.view === 'txf') {
      Suite.views.renderTxf();
    }
  }

  // BOOT
  let booted = false;
  function boot() {
    if (booted) {
      return;
    }
    booted = true;
    StateStore.init(Suite.model.defaults());
    document.documentElement.setAttribute('data-theme', UI.theme);
    Suite.utils.applyTheme();
    Suite.utils.$$('.switch[data-bind]').forEach(sw => sw.classList.add('switch'));
    Suite.views.bindConfig();
    Suite.views.bindConfigSelects();
    Suite.views.bindSyncFields();
    Suite.views.bindPropsInputs();
    Suite.views.bindImportExport();
    Suite.views.bindToolbar();
    Suite.views.initDocking();
    Suite.views.renderPerms();
    Suite.views.renderKernel();
    Suite.views.switchView('iflow');
    Suite.views.renderSidebar();
    Suite.views.renderStatus();
    Suite.views.renderProps();
    Suite.views.buildCrumbs();
    Suite.views.bindHotkeys();
    // precompute node heights for edges
    for (const n of StateStore.getState().graph.nodes) {
      n.h = Suite.views.KIND_H[n.kind] || 52;
    }
    Suite.views.renderCanvas('iflow');
    Suite.views.renderStatus();
    console.log('%cSuite Web Editor v2.1 Â· schema v2.2 Â· ServiceLoader id', 'color:#6ee7a0');
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  global.Suite = global.Suite || {};
  global.Suite.app = {
    state: () => StateStore.getState(),
    save: StateStore.save,
    undo: StateStore.undo,
    redo: StateStore.redo,
  };
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, { switchView, buildCrumbs, renderAll, changed, boot });
})(window || this);

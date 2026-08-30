/* toolbar.js — botones principales, hotkeys */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const $$ = global.Suite.utils.$$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;
  const UI = global.Suite.i18n.UI;

  function bindToolbar() {
    $('#undoBtn').onclick = StateStore.undo;
    $('#redoBtn').onclick = StateStore.redo;
    $('#validBtn').onclick = () => {
      const v = Suite.validate.validate(StateStore.getState());
      Suite.utils.toast(
        (v.errors ? v.errors + ' error(s) · ' : '') +
          (v.warnings ? v.warnings + ' warning(s)' : '') +
          (v.errors === 0 && v.warnings === 0 ? '✓ todo ok' : ''),
        v.errors ? 'err' : v.warnings ? 'warn' : 'ok'
      );
      for (const iss of v.issues.map(i => '· ' + i.path + ': ' + i.msg)) {
        console.log(iss);
      }
      Suite.views.renderStatus();
    };
    // #dlBtn handler is set up in importExport.js bindImportExport()
    $('#importBtn').onclick = () => $('#fileInput').click();
    $('#fitView').onclick = () => Suite.views.fitView();
    $('#zoomIn').onclick = () => zoomAt(vwC(), vhC(), 1.2);
    $('#zoomOut').onclick = () => zoomAt(vwC(), vhC(), 1 / 1.2);
    $('#dupBtn').onclick = Suite.views.dupSelected;
    $('#delBtn').onclick = Suite.views.delSelected;
    $('#previewBtn').addEventListener('click', () => {
      Suite.views.switchView('preview');
    });
    $('#themeBtn').onclick = () => {
      UI.theme = UI.theme === 'dark' ? 'light' : 'dark';
      localStorage.setItem('suite-editor-theme', UI.theme);
      Suite.utils.applyTheme();
    };
    $('#langBtn').onclick = () => {
      UI.lang = UI.lang === 'en' ? 'es' : 'en';
      localStorage.setItem('suite-editor-lang', UI.lang);
      Suite.utils.applyLang();
    };
  }

  function vwC() {
    const vp = document.querySelector('.viewport[data-canvas="iflow"]');
    const r = vp.getBoundingClientRect();
    return r.width / 2;
  }
  function vhC() {
    const vp = document.querySelector('.viewport[data-canvas="iflow"]');
    const r = vp.getBoundingClientRect();
    return r.height / 2;
  }

  function zoomAt(px, py, factor) {
    const nz = Math.min(4, Math.max(0.25, UI.zoom * factor));
    const k = nz / UI.zoom;
    UI.panX = px - (px - UI.panX) * k;
    UI.panY = py - (py - UI.panY) * k;
    Suite.views.applyTransform(document.querySelector('.viewport[data-canvas="iflow"]'));
    Suite.views.renderMinimap(
      document.querySelector('.viewport[data-canvas="iflow"]'),
      document.querySelector('.viewport[data-canvas="iflow"]')._stage
    );
  }

  function bindHotkeys() {
    document.addEventListener('keydown', e => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !e.shiftKey) {
        e.preventDefault();
        StateStore.undo();
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && e.shiftKey) {
        e.preventDefault();
        StateStore.redo();
      }
      if (e.key === 'Delete' || e.key === 'Backspace') {
        if (document.activeElement && /INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) {
          return;
        }
        if (UI.sel) {
          Suite.views.delSelected();
        }
      }
      if (e.key === 'Escape') {
        UI.connect = null;
        document.querySelectorAll('.port.on').forEach(p => p.classList.remove('on'));
      }
    });
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, { bindToolbar, vwC, vhC, zoomAt, bindHotkeys });
})(window || this);

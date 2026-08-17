/* docking.js — GIMP-style docking para 5 paneles */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const $$ = global.Suite.utils.$$;
  const Suite = global.Suite;
  const UI = global.Suite.i18n.UI;

  const DOCK_KEY = 'suite-editor-dock-v1';
  const DOCKABLES = ['sidebar', 'toolbar1', 'toolbar2', 'propPanel', 'previewPanel'];

  function dockDefaults() {
    const d = {};
    DOCKABLES.forEach(id => {
      d[id] = { docked: true, x: 0, y: 0, w: 0, h: 0 };
    });
    return d;
  }

  let Dock = dockDefaults();

  function loadDock() {
    try {
      const s = JSON.parse(localStorage.getItem(DOCK_KEY));
      if (s && typeof s === 'object') {
        Dock = Object.assign(dockDefaults(), s);
      }
    } catch (e) {
      /* ignore */
    }
  }

  function saveDock() {
    try {
      localStorage.setItem(DOCK_KEY, JSON.stringify(Dock));
    } catch (e) {
      /* ignore */
    }
  }

  function initDocking() {
    loadDock();
    DOCKABLES.forEach(id => applyDockUI(id));

    // Sidebar head
    const sb = $('#sidebar');
    if (sb) {
      const head = document.createElement('div');
      head.className = 's-head';
      head.innerHTML = '<span>Suite</span><button class="tbtn" id="dockSidebar" title="Desacoplar">⌏</button>';
      sb.insertBefore(head, sb.firstChild);
      sb.dataset.dockable = 'sidebar';
      sb.style.touchAction = 'none';
    }
    // Toolbar1
    const tb1 = $('#toolbar1');
    if (tb1) {
      const grip = document.createElement('span');
      grip.className = 'dock-grip';
      grip.innerHTML = '<button class="tbtn" id="dockTb1" title="Desacoplar toolbar1">⌏</button>';
      tb1.insertBefore(grip, tb1.firstChild);
      tb1.dataset.dockable = 'toolbar1';
      tb1.style.touchAction = 'none';
    }
    // Toolbar2
    const tb2 = $('#toolbar2');
    if (tb2) {
      const grip = document.createElement('span');
      grip.className = 'dock-grip';
      grip.innerHTML = '<button class="tbtn" id="dockTb2" title="Desacoplar toolbar2">⌏</button>';
      tb2.insertBefore(grip, tb2.firstChild);
      tb2.dataset.dockable = 'toolbar2';
      tb2.style.touchAction = 'none';
    }
    // Prop panel
    const pp = $('#propPanel');
    if (pp) {
      const btn = document.createElement('button');
      btn.className = 'tbtn';
      btn.id = 'propDetach';
      btn.title = 'Desacoplar propiedades';
      btn.textContent = '⌏';
      pp.appendChild(btn);
      pp.dataset.dockable = 'propPanel';
      pp.style.touchAction = 'none';
    }
    // Preview panel
    const pv = $('#previewPanel');
    if (pv) {
      const pvHead = pv.querySelector('.pv-head') || pv.firstElementChild;
      if (pvHead && !pvHead.id) {
        pvHead.classList.add('pv-head');
      }
      const btn = document.createElement('button');
      btn.className = 'tbtn';
      btn.id = 'pvDetach';
      btn.title = 'Desacoplar previsualización';
      btn.textContent = '⌏';
      if (pvHead) {
        pvHead.appendChild(btn);
      }
      pv.dataset.dockable = 'previewPanel';
      pv.style.touchAction = 'none';
    }

    // Resize handles
    DOCKABLES.forEach(id => {
      const el = $('#' + id);
      if (!el) {
        return;
      }
      const rsz = document.createElement('div');
      rsz.className = 'rsz';
      rsz.dataset.for = id;
      el.appendChild(rsz);
    });

    // Delegated handlers
    document.addEventListener('mousedown', e => {
      const btn = e.target.closest(
        '[id^="dock"][id$="Detach"], [id^="dock"][id$="Sidebar"], [id^="dock"][id$="Tb1"], [id^="dock"][id$="Tb2"]'
      );
      if (btn) {
        const id = btn.id.replace('dock', '').replace('Detach', '');
        const map = {
          Sidebar: 'sidebar',
          Tb1: 'toolbar1',
          Tb2: 'toolbar2',
          PropDetach: 'propPanel',
          PvDetach: 'previewPanel',
        };
        toggleDock(map[id] || id);
      }
    });
    document.addEventListener('mousedown', e => {
      const grip = e.target.closest('.dock-grip, .s-head, .pv-head');
      if (grip) {
        const panel = grip.closest('[data-dockable]');
        if (panel && panel.dataset.dockable) {
          startDrag(panel.dataset.dockable, e);
        }
      }
    });
    document.addEventListener('mousedown', e => {
      const rsz = e.target.closest('.rsz');
      if (rsz) {
        startResize(rsz.dataset.for, e);
      }
    });
  }

  function applyDockUI(id) {
    const el = $('#' + id);
    if (!el) {
      return;
    }
    const d = Dock[id];
    if (!d) {
      return;
    }
    if (d.docked) {
      el.classList.remove('floating');
      el.style.position = '';
      el.style.left = '';
      el.style.top = '';
      el.style.width = '';
      el.style.height = '';
      el.style.zIndex = '';
      const btn = el.querySelector('[id^="dock"], #propDetach, #pvDetach');
      if (btn) {
        btn.textContent = '⌏';
      }
    } else {
      el.classList.add('floating');
      el.style.position = 'fixed';
      el.style.left = d.x + 'px';
      el.style.top = d.y + 'px';
      if (d.w) {
        el.style.width = d.w + 'px';
      }
      if (d.h) {
        el.style.height = d.h + 'px';
      }
      el.style.zIndex = '55';
      const btn = el.querySelector('[id^="dock"], #propDetach, #pvDetach');
      if (btn) {
        btn.textContent = '▗';
      }
    }
  }

  function toggleDock(id) {
    const d = Dock[id];
    if (!d) {
      return;
    }
    d.docked = !d.docked;
    if (d.docked) {
      d.x = 0;
      d.y = 0;
      d.w = 0;
      d.h = 0;
    }
    applyDockUI(id);
    saveDock();
    // Special: toolbar2 visibility depends on view + docked
    if (id === 'toolbar2') {
      $('#toolbar2').style.display = UI.view === 'iflow' || UI.view === 'txf' || !d.docked ? '' : 'none';
    }
  }

  function startDrag(id, e) {
    if (e.target.tagName === 'BUTTON') {
      return;
    }
    const el = $('#' + id);
    if (!el || Dock[id].docked) {
      return;
    }
    e.preventDefault();
    const rect = el.getBoundingClientRect();
    const ox = e.clientX - rect.left,
      oy = e.clientY - rect.top;
    function move(ev) {
      Dock[id].x = ev.clientX - ox;
      Dock[id].y = ev.clientY - oy;
      applyDockUI(id);
    }
    function up() {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', up);
      saveDock();
    }
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
  }

  function startResize(id, e) {
    const el = $('#' + id);
    if (!el || Dock[id].docked) {
      return;
    }
    e.preventDefault();
    const startW = el.offsetWidth,
      startH = el.offsetHeight;
    const startX = e.clientX,
      startY = e.clientY;
    function move(ev) {
      Dock[id].w = Math.max(200, startW + (ev.clientX - startX));
      Dock[id].h = Math.max(150, startH + (ev.clientY - startY));
      applyDockUI(id);
    }
    function up() {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', up);
      saveDock();
    }
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
  }

  function renderAdapt(id) {
    const el = $('#' + id);
    if (!el) {
      return;
    }
    if (id === 'sidebar') {
      const sb = el.querySelector('#sidebarBody');
      if (sb) {
        sb.style.height = el.offsetHeight - 40 + 'px';
      }
    }
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, {
    initDocking,
    applyDockUI,
    toggleDock,
    startDrag,
    startResize,
    renderAdapt,
    loadDock,
    saveDock,
    Dock,
    DOCK_KEY,
    DOCKABLES,
  });
})(window || this);

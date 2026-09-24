/* canvas.js — lienzo (iflow): render, drag, edges, viewport, minimap */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const $$ = global.Suite.utils.$$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;
  const UI = global.Suite.i18n.UI;

  const KIND_H = { input: 52, cond: 74, transform: 70, loop: 52, sleep: 52, output: 52, redirect: 52 };

  function renderCanvas(name) {
    const vp = document.querySelector('.viewport[data-canvas="' + name + '"]');
    if (!vp) {
      return;
    }
    let stage = vp.querySelector('.stage');
    const st = StateStore.getState();
    const nodes = st.graph.nodes || [];
    if (!stage) {
      stage = document.createElement('div');
      stage.className = 'stage';
      vp.appendChild(stage);
      const grid = document.createElement('div');
      grid.className = 'grid-bg';
      stage.appendChild(grid);
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('class', 'edges');
      svg.setAttribute('width', '10000');
      svg.setAttribute('height', '10000');
      stage.appendChild(svg);
      vp._svg = svg;
    }

    const byId = new Map(nodes.map(n => [n.id, n]));
    const existing = Array.from(stage.querySelectorAll('.node'));
    const existingById = new Map(existing.map(el => [el.dataset.id, el]));

    for (const el of existing) {
      if (!byId.has(el.dataset.id)) {
        el.remove();
      }
    }
    for (const n of nodes) {
      const el = existingById.get(n.id);
      if (!el) {
        stage.appendChild(makeNode(n));
        bindNode(stage.lastChild);
      } else {
        updateNode(el, n);
      }
    }

    drawEdges(stage);
    applyTransform(vp);
    renderMinimap(vp, stage);
    bindViewport(vp, stage, vp._svg);
    vp._stage = stage;
  }

  function updateNode(el, n) {
    const h = renderHeight(n);
    n.h = h;
    const cls = 'node ' + n.kind + (UI.sel && UI.sel.type === 'node' && UI.sel.id === n.id ? ' selected' : '');
    if (el.className !== cls) {
      el.className = cls;
    }
    if (el.style.left !== n.x + 'px') {
      el.style.left = n.x + 'px';
    }
    if (el.style.top !== n.y + 'px') {
      el.style.top = n.y + 'px';
    }
    if (el.style.height !== h + 'px') {
      el.style.height = h + 'px';
    }
    if (el.style.width !== (n.w || 150) + 'px') {
      el.style.width = (n.w || 150) + 'px';
    }
    const l = el.querySelector('.n-label');
    const label = n.label || n.id;
    if (l && l.textContent !== label) {
      l.textContent = label;
    }
    const body = renderBody(n);
    const b = el.querySelector('.n-body');
    if (body) {
      if (!b) {
        el.querySelector('.n-head').insertAdjacentHTML('afterend', '<div class="n-body">' + body + '</div>');
      } else if (b.innerHTML !== body) {
        b.innerHTML = body;
      }
    } else if (b) {
      b.remove();
    }
    const f = el.querySelector('.n-foot');
    if (f) {
      const spans = f.querySelectorAll('span');
      if (spans[0] && spans[0].textContent !== n.id) {
        spans[0].textContent = n.id;
      }
      if (spans[1] && spans[1].textContent !== global.Suite.i18n.t('kind_' + n.kind)) {
        spans[1].textContent = global.Suite.i18n.t('kind_' + n.kind);
      }
    }
  }

  function makeNode(n) {
    const el = document.createElement('div');
    const h = renderHeight(n);
    n.h = h;
    el.className = 'node ' + n.kind + (UI.sel && UI.sel.type === 'node' && UI.sel.id === n.id ? ' selected' : '');
    el.dataset.id = n.id;
    const body = renderBody(n);
    el.innerHTML =
      '<div class="n-head"><span class="dot"></span><span class="n-label">' +
      Suite.utils.esc(n.label || n.id) +
      '</span></div>' +
      (body ? '<div class="n-body">' + body + '</div>' : '') +
      '<div class="n-foot"><span>' +
      Suite.utils.esc(n.id) +
      '</span><span>' +
      global.Suite.i18n.t('kind_' + n.kind) +
      '</span></div>' +
      '<span class="port top" data-port="top"></span><span class="port bot" data-port="bot"></span>';
    el.style.left = n.x + 'px';
    el.style.top = n.y + 'px';
    el.style.width = (n.w || 150) + 'px';
    el.style.height = h + 'px';
    return el;
  }

  function renderHeight(n) {
    const base = KIND_H[n.kind] || 52;
    if (n.kind === 'transform') {
      return 64 + (n.transforms || []).length * 18;
    }
    if (n.kind === 'cond' && n.matcher) {
      return 62;
    }
    return base;
  }

  function renderBody(n) {
    if (n.kind === 'transform') {
      return (n.transforms || [])
        .map(tr => {
          if (tr.op === 'rewrite') {
            return '<div class="f">○ ' + Suite.utils.esc((tr.template || '').slice(0, 26)) + '</div>';
          }
          if (tr.op === 'sounds') {
            return '<div class="f">🔊 ' + Suite.utils.esc((tr.add || []).join(',')) + '</div>';
          }
          if (tr.op === 'sleep') {
            return '<div class="f">⏳ ' + Suite.utils.esc(String(tr.millis)) + 'ms</div>';
          }
          return '';
        })
        .join('');
    }
    if (n.kind === 'cond' && n.matcher) {
      return (
        '<div class="f">≡ ' +
        Suite.utils.esc(
          Object.entries(n.matcher)
            .map(([k, v]) => k + '=' + v)
            .join(' ')
        ) +
        '</div>'
      );
    }
    if (n.kind === 'redirect' && n.target) {
      return '<div class="f">→ ' + Suite.utils.esc(n.target.channel) + '</div>';
    }
    return '';
  }

  async function bindNode(el) {
    const st = StateStore.getState();
    const n = st.graph.nodes.find(x => x.id === el.dataset.id);
    el.querySelector('.n-head').addEventListener('mousedown', e => {
      if (e.button !== 0) {
        return;
      }
      startNodeDrag(e, el, n.id);
    });
    el.addEventListener('click', e => {
      if (e.target.closest('.port')) {
        return;
      }
      UI.sel = { type: 'node', id: n.id };
      document.querySelectorAll('.node').forEach(x => x.classList.toggle('selected', x === el));
      Suite.views.renderProps();
    });
    bindPort(el.querySelector('.port.top'), n.id, 'top');
    bindPort(el.querySelector('.port.bot'), n.id, 'bot');
  }

  function startNodeDrag(ev, el, id) {
    ev.preventDefault();
    const vp = el.closest('.viewport');
    const start = StateStore.getState().graph.nodes.find(x => x.id === id) || { x: 0, y: 0 };
    const startX = ev.clientX,
      startY = ev.clientY;
    const ox = start.x,
      oy = start.y;
    let moved = false,
      lastNX = ox,
      lastNY = oy;
    function move(e) {
      moved = true;
      lastNX = snap(ox + (e.clientX - startX) / UI.zoom);
      lastNY = snap(oy + (e.clientY - startY) / UI.zoom);
      rafNode(() => {
        el.style.left = lastNX + 'px';
        el.style.top = lastNY + 'px';
        const live = { id, x: lastNX, y: lastNY };
        drawEdges(vp._stage, live);
        renderMinimap(vp, vp._stage, live);
      });
    }
    function up() {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', up);
      if (moved && (lastNX !== ox || lastNY !== oy)) {
        el.style.left = lastNX + 'px';
        el.style.top = lastNY + 'px';
        StateStore.mutate('move', () => {
          const s = StateStore.getState();
          const t = s.graph.nodes.find(x => x.id === id);
          if (t) {
            t.x = lastNX;
            t.y = lastNY;
          }
        });
      }
    }
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
  }

  function snap(v) {
    return Math.round(v / 20) * 20;
  }

  function bindPort(portEl, id, side) {
    portEl.addEventListener('mousedown', e => {
      e.stopPropagation();
      e.preventDefault();
      if (UI.connect) {
        completeConnect(id, side);
        return;
      }
      UI.connect = { from: id, fromPort: side };
      portEl.classList.add('on');
    });
  }

  function completeConnect(id, side) {
    const a = UI.connect.from,
      aSide = UI.connect.fromPort;
    UI.connect = null;
    const from = aSide === 'bot' ? a : id;
    const to = aSide === 'bot' ? id : a;
    if (from === to) {
      return;
    }
    StateStore.mutate('edge', () => Suite.model.addEdge(StateStore.getState(), from, to));
  }

  function drawEdges(stage, live) {
    const st = StateStore.getState();
    const svg = stage.querySelector('svg.edges');
    if (!svg) {
      return;
    }
    svg.innerHTML = '';
    const nodeById = new Map(st.graph.nodes.map(n => [n.id, n]));
    if (live && live.id) {
      const n = nodeById.get(live.id);
      if (n) {
        n.x = live.x;
        n.y = live.y;
      }
    }
    const cycleEdges = cycleSet();
    for (const e of st.graph.edges || []) {
      const f = nodeById.get(e.from),
        t = nodeById.get(e.to);
      if (!f || !t) {
        continue;
      }
      const x1 = f.x + (f.w || 150) / 2,
        y1 = f.y + renderHeight(f);
      const x2 = t.x + (t.w || 150) / 2,
        y2 = t.y;
      const d = Math.max(42, Math.abs(x2 - x1) / 2, 40);
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute(
        'd',
        'M' + x1 + ' ' + y1 + ' C ' + x1 + ' ' + (y1 + d) + ', ' + x2 + ' ' + (y2 - d) + ', ' + x2 + ' ' + y2
      );
      path.setAttribute('class', 'edge' + (cycleEdges.has(e.from + '>' + e.to) ? ' loop' : ''));
      path.dataset.from = e.from;
      path.dataset.to = e.to;
      path.addEventListener('click', ev => {
        ev.stopPropagation();
        UI.sel = { type: 'edge', from: e.from, to: e.to };
        Suite.views.renderProps();
      });
      svg.appendChild(path);
    }
  }

  function cycleSet() {
    const st = StateStore.getState();
    const nodes = st.graph.nodes.map(n => n.id);
    const out = new Set();
    const adj = new Map(nodes.map(id => [id, []]));
    const edges = st.graph.edges || [];
    for (const e of edges) {
      if (adj.has(e.from)) {
        adj.get(e.from).push(e.to);
      }
    }
    const visited = new Set(),
      stack = [];
    function dfs(u) {
      visited.add(u);
      stack.push(u);
      for (const v of adj.get(u) || []) {
        if (!nodes.includes(v)) {
          continue;
        }
        if (!visited.has(v)) {
          if (dfs(v)) {
            return true;
          }
        } else if (stack.includes(v)) {
          const i = stack.indexOf(v);
          const cycle = stack.slice(i);
          const guard = cycle.some(id => {
            const n = st.graph.nodes.find(x => x.id === id);
            return n && (n.kind === 'cond' || n.kind === 'sleep');
          });
          if (!guard) {
            out.add(cycle.join(' → '));
          }
          return true;
        }
      }
      stack.pop();
      return false;
    }
    for (const n of st.graph.nodes) {
      if (!visited.has(n.id)) {
        dfs(n.id);
      }
    }
    return out;
  }

  function applyTransform(vp) {
    const stage = vp._stage || vp.querySelector('.stage');
    if (!stage) {
      return;
    }
    stage.style.transform = 'translate(' + UI.panX + 'px,' + UI.panY + 'px) scale(' + UI.zoom + ')';
    $('#zoomVal').textContent = Math.round(UI.zoom * 100) + '%';
    $('#sbarZoom').textContent = Math.round(UI.zoom * 100) + '%';
  }

  function makeRaf() {
    let raf = 0,
      latest;
    return (fn, ...args) => {
      latest = { fn, args };
      if (raf) {
        return;
      }
      raf = requestAnimationFrame(() => {
        raf = 0;
        if (latest) {
          const { fn: f, args: a } = latest;
          f(...a);
        }
      });
    };
  }

  const rafPan = makeRaf();
  const rafTemp = makeRaf();
  const rafNode = makeRaf();

  function bindViewport(vp, stage, svg) {
    if (vp.dataset.bound) {
      return;
    }
    vp.dataset.bound = '1';
    const vps = () => vp._stage || stage;
    vp.addEventListener(
      'wheel',
      e => {
        if (!e.ctrlKey) {
          return;
        }
        e.preventDefault();
        const rect = vp.getBoundingClientRect();
        const px = e.clientX - rect.left,
          py = e.clientY - rect.top;
        zoomAt(px, py, e.deltaY < 0 ? 1.1 : 1 / 1.1);
      },
      { passive: false }
    );
    let space = false,
      dragging = false,
      sx = 0,
      sy = 0,
      ox = 0,
      oy = 0;
    window.addEventListener('keydown', e => {
      if (e.code === 'Space' && !space) {
        space = true;
        vp.style.cursor = 'grabbing';
      }
    });
    window.addEventListener('keyup', e => {
      if (e.code === 'Space') {
        space = false;
        if (!dragging) {
          vp.style.cursor = '';
        }
      }
    });
    vp.addEventListener('mousedown', e => {
      if (e.button !== 0 || e.target.closest('.node')) {
        return;
      }
      dragging = true;
      sx = e.clientX;
      sy = e.clientY;
      ox = UI.panX;
      oy = UI.panY;
      vp.style.cursor = 'grabbing';
    });
    window.addEventListener('mousemove', e => {
      if (dragging) {
        rafPan(() => {
          UI.panX = ox + (e.clientX - sx);
          UI.panY = oy + (e.clientY - sy);
          applyTransform(vp);
          renderMinimap(vp, vps());
        });
        return;
      }
      rafTemp(() => renderTempEdge(vp, vps(), e));
    });
    window.addEventListener('mouseup', () => {
      if (dragging) {
        dragging = false;
        UI.panX = ox + (e.clientX - sx);
        UI.panY = oy + (e.clientY - sy);
        applyTransform(vp);
        renderMinimap(vp, vps());
      }
      vp.style.cursor = space ? 'grabbing' : '';
    });
    vp.addEventListener('drop', onCanvasDrop);
    vp.addEventListener('dragover', e => e.preventDefault());
  }

  function zoomAt(px, py, factor) {
    const nz = Math.min(4, Math.max(0.25, UI.zoom * factor));
    const k = nz / UI.zoom;
    UI.panX = px - (px - UI.panX) * k;
    UI.panY = py - (py - UI.panY) * k;
    applyTransform(document.querySelector('.viewport[data-canvas="iflow"]'));
    renderMinimap(
      document.querySelector('.viewport[data-canvas="iflow"]'),
      document.querySelector('.viewport[data-canvas="iflow"]')._stage
    );
  }

  function renderTempEdge(vp, stage, e) {
    const svg = vp._svg;
    if (!svg) {
      return;
    }
    const st = StateStore.getState();
    let te = svg.querySelector('.temp-edge');
    if (!UI.connect) {
      if (te) {
        te.remove();
      }
      return;
    }
    const fromNode = st.graph.nodes.find(n => n.id === UI.connect.from);
    if (!fromNode) {
      return;
    }
    if (!te) {
      te = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      te.setAttribute('class', 'temp-edge');
      svg.appendChild(te);
    }
    const x1 = fromNode.x + (fromNode.w || 150) / 2;
    const y1 = fromNode.y + (UI.connect.fromPort === 'bot' ? fromNode.h || 52 : 0);
    const rect = vp.getBoundingClientRect();
    const x2 = (e.clientX - rect.left - UI.panX) / UI.zoom;
    const y2 = (e.clientY - rect.top - UI.panY) / UI.zoom;
    const d = Math.max(42, Math.abs(x2 - x1) / 2);
    te.setAttribute(
      'd',
      'M' + x1 + ' ' + y1 + ' C ' + x1 + ' ' + (y1 + d) + ', ' + x2 + ' ' + (y2 - d) + ', ' + x2 + ' ' + y2
    );
  }

  function onCanvasDrop(e) {
    e.preventDefault();
    const kind = e.dataTransfer.getData('text/suite-kind');
    const token = e.dataTransfer.getData('text/suite-token');
    const dchip = e.dataTransfer.getData('text/suite-dchip');
    const vp = document.querySelector('.viewport[data-canvas="iflow"]');
    const rect = vp.getBoundingClientRect();
    const x = snap((e.clientX - rect.left - UI.panX) / UI.zoom - 60);
    const y = snap((e.clientY - rect.top - UI.panY) / UI.zoom - 20);
    if (kind) {
      StateStore.mutate('add node', () => {
        const st = StateStore.getState();
        const n = Suite.model.addNode(st, kind, null, x, y);
        if (kind === 'transform') {
          n.transforms = [{ op: 'rewrite', template: '<green>%content%</green>' }];
        }
        if (kind === 'cond') {
          n.matcher = { channel: Object.keys(st.channels)[0] || 'chat.global' };
        }
        n.x = x;
        n.y = y;
      });
    }
    if (token && UI.sel && UI.sel.type === 'channel') {
      StateStore.mutate('token drop', () => {
        const st = StateStore.getState();
        const c = st.channels[UI.sel.key];
        if (c && c.messages.length) {
          c.messages[c.messages.length - 1] += token;
        }
      });
    }
    if (dchip && vp.dataset.canvas === 'txf') {
      addChannelFromTemplate(dchip);
    }
  }

  function renderMinimap(vp, stage, live) {
    const st = StateStore.getState();
    const mm = vp.querySelector('.minimap');
    if (!mm) {
      return;
    }
    const nodes = st.graph.nodes || [];
    if (live && live.id) {
      const n = nodes.find(x => x.id === live.id);
      if (n) {
        n.x = live.x;
        n.y = live.y;
      }
    }
    if (!nodes.length) {
      mm.innerHTML = '';
      return;
    }
    let minX = Infinity,
      minY = Infinity,
      maxX = -Infinity,
      maxY = -Infinity;
    for (const n of nodes) {
      const w = n.w || 150,
        h = renderHeight(n);
      if (n.x < minX) {
        minX = n.x;
      }
      if (n.y < minY) {
        minY = n.y;
      }
      if (n.x + w > maxX) {
        maxX = n.x + w;
      }
      if (n.y + h > maxY) {
        maxY = n.y + h;
      }
    }
    const bw = maxX - minX || 1,
      bh = maxY - minY || 1;
    const mw = 150,
      mh = 100;
    const scale = Math.min(mw / bw, mh / bh, 1.5);
    const offX = (mw - bw * scale) / 2 - minX * scale;
    const offY = (mh - bh * scale) / 2 - minY * scale;
    mm.innerHTML = '<span class="minimap-title">minimap</span>';
    for (const n of nodes) {
      const el = document.createElement('div');
      el.className = 'mm-node';
      el.style.width = (n.w || 150) * scale + 'px';
      el.style.height = renderHeight(n) * Math.min(scale, 0.2) + 'px';
      el.style.left = n.x * scale + offX + 'px';
      el.style.top = n.y * scale + offY + 'px';
      el.style.background = kindColor(n.kind);
      mm.appendChild(el);
    }
  }

  function kindColor(k) {
    return (
      {
        input: 'var(--green)',
        cond: 'var(--amber)',
        transform: 'var(--purple)',
        loop: 'var(--blue)',
        sleep: 'var(--red)',
        output: 'var(--green)',
        redirect: 'var(--red)',
      }[k] || 'var(--line2)'
    );
  }

  function fitView() {
    const st = StateStore.getState();
    const nodes = st.graph.nodes || [];
    if (!nodes.length) {
      return;
    }
    let minX = Infinity,
      minY = Infinity,
      maxX = -Infinity,
      maxY = -Infinity;
    for (const n of nodes) {
      if (n.x < minX) {
        minX = n.x;
      }
      if (n.y < minY) {
        minY = n.y;
      }
      if (n.x + (n.w || 150) > maxX) {
        maxX = n.x + (n.w || 150);
      }
      if (n.y + renderHeight(n) > maxY) {
        maxY = n.y + renderHeight(n);
      }
    }
    const vp = document.querySelector('.viewport[data-canvas="iflow"]');
    const r = vp.getBoundingClientRect();
    UI.zoom = Math.min(1.5, Math.max(0.25, Math.min((r.width - 80) / (maxX - minX), (r.height - 80) / (maxY - minY))));
    UI.panX = (r.width - UI.zoom * (maxX - minX)) / 2 - minX * UI.zoom;
    UI.panY = (r.height - UI.zoom * (maxY - minY)) / 2 - minY * UI.zoom;
    applyTransform(vp);
    renderMinimap(vp, vp._stage);
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, {
    renderCanvas,
    updateNode,
    makeNode,
    renderHeight,
    renderBody,
    bindNode,
    startNodeDrag,
    snap,
    bindPort,
    completeConnect,
    drawEdges,
    cycleSet,
    applyTransform,
    makeRaf,
    bindViewport,
    zoomAt,
    renderTempEdge,
    onCanvasDrop,
    renderMinimap,
    kindColor,
    fitView,
    rafPan,
    rafTemp,
    rafNode,
    KIND_H,
  });
})(window || this);

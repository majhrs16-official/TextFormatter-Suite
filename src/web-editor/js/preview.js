/* preview.js — render MiniMessage limitado + simulación del pipeline + sonido.
 * Es un port JS del motor Java de la suite (fixtures dorados compartidos). */
(function (global) {
  'use strict';

  const COLORS = {
    black: '#000',
    dark_blue: '#1e3a8a',
    dark_green: '#166534',
    dark_aqua: '#0f766e',
    dark_red: '#7f1d1d',
    dark_purple: '#581c87',
    gold: '#f59e0b',
    gray: '#9ca3af',
    dark_gray: '#4b5563',
    blue: '#3b82f6',
    green: '#22c55e',
    aqua: '#06b6d4',
    red: '#ef4444',
    light_purple: '#a855f7',
    yellow: '#eab308',
    white: '#f8fafc',
    purple: '#a855f7',
  };
  const FIXES = {
    bold: '<b>',
    italic: '<i>',
    underlined: '<u>',
    strikethrough: '',
    obfuscated: '',
    reset: '',
    rainbow: '',
    gradient: '',
  };
  function esc(s) {
    return String(s).replace(/[&<>]/g, c => ({ '&': '&', '<': '<', '>': '>' })[c]);
  }

  // Renderiza texto con tokens %var% y etiquetas MiniMessage limitadas.
  function renderMini(text, vars) {
    vars = vars || {};
    // 1) tokens -> valores escapan individualmente (no rompen etiquetas)
    const raw = String(text === null ? '' : text).replace(/%([a-zA-Z_][a-zA-Z0-9_]*)%/g, (m, k) =>
      vars[k] !== undefined ? '\u0001' + vars[k] + '\u0002' : m
    );
    // 2) parsea etiquetas MiniMessage sobre texto crudo (cada etiqueta cerrada
    //    se convierte en taxonomía HTML; los textos pasan por esc())
    const out = [];
    const re = /<(\/?)([a-zA-Z0-9_]+)(>|:\s*[^>]*>)/g;
    let last = 0,
      m;
    while ((m = re.exec(raw))) {
      const close = m[1] === '/',
        tag = m[2].toLowerCase(),
        hasArg = !m[3].startsWith('>');
      out.push(tagHtml(esc(raw.slice(last, m.index)), close, tag, hasArg));
      last = m.index + m[0].length;
    }
    out.push(tagHtml(esc(raw.slice(last)), false, '', false));
    return out
      .join('')
      .replace(/\u0001/g, '')
      .replace(/\u0002/g, '');
  }
  function tagHtml(escaped, close, tag, hasArg) {
    if (!tag) {
      return escaped;
    }
    if (close) {
      return escaped + (tag === 'tr' ? '</span>' : '');
    }
    if (hasArg) {
      return escaped;
    } // span con args se ignora (font style no soportado)
    if (COLORS[tag]) {
      return escaped + '<span style="color:' + COLORS[tag] + '">';
    }
    if (FIXES[tag] !== undefined) {
      return escaped + FIXES[tag];
    }

    // tags sin cierre (formato) se ignoran
    return escaped;
  }

  // Simulación del pipeline iFlow: recorre graph.nodes/edges y aplica transforms.
  function simulate(state, channelName, text, options) {
    options = options || {};
    const dedup = options.dedup !== false;
    const maxSteps = state.graph?.guard?.['max-steps'] ?? 512;

    if (!state.channels || !state.channels[channelName]) {
      return { ok: false, reason: 'Channel not found: ' + channelName };
    }
    const channel = state.channels[channelName];
    const vars = tokens(channel);
    vars.content = text;

    // Build adjacency list
    const adj = new Map();
    for (const e of state.graph?.edges || []) {
      if (!adj.has(e.from)) {
        adj.set(e.from, []);
      }
      adj.get(e.from).push(e.to);
    }

    // Find entry nodes matching channel
    const entries = (state.graph?.nodes || []).filter(n => n.kind === 'input' && n.label === channelName);
    if (!entries.length) {
      return { ok: false, reason: 'No input node for channel: ' + channelName };
    }

    // BFS simulation
    const queue = entries.map(n => ({
      id: n.id,
      path: [n.id],
      msg: text,
      sounds: [],
      steps: 0,
    }));
    const seen = new Set();
    const results = [];
    const order = [];

    while (queue.length) {
      const cur = queue.shift();
      if (!cur) continue;

      const node = (state.graph?.nodes || []).find(x => x.id === cur.id);
      if (!node) continue;

      let outText = cur.msg;
      let sounds = [...cur.sounds];

      // Apply node transforms
      if (node.kind === 'transform') {
        for (const t of node.transforms || []) {
          if (t.op === 'rewrite') {
            outText = Suite.preview.renderMini(t.template, vars);
            vars.content = outText;
          } else if (t.op === 'sounds') {
            for (const a of t.add || []) {
              sounds.push({ name: a, volume: 1.0, pitch: 1.0 });
            }
            for (const r of t.remove || []) {
              sounds = sounds.filter(s => s !== r);
            }
          }
        }
      } else if (node.kind === 'sleep') {
        /* el delay no cambia el mensaje */
      }

      // Output/redirect nodes produce results
      if (node.kind === 'output' || node.kind === 'redirect') {
        const key = (options.dedup !== false ? node.id : '') + '/' + channelName + '/' + outText;
        if (!seen.has(key)) {
          seen.add(key);
          results.push({
            target: node.label || (node.target && node.target.channel),
            id: node.id,
            text: outText,
            path: cur.path,
            sounds,
          });
        }
        continue;
      }

      // Traverse edges
      const nexts = adj.get(node.id) || [];
      for (const nxt of nexts) {
        const steps = cur.steps + 1;
        if (steps > maxSteps) {
          results.push({
            target: '(guard max-steps)',
            id: node.id,
            text: outText,
            path: cur.path.concat(nxt),
            sounds,
            guard: true,
          });
          continue;
        }
        const key = nxt + '/' + channelName + '/' + outText;
        const dupKey = 'q:' + key;
        if (dedup && seen.has(dupKey)) {
          continue;
        }
        if (dedup) {
          seen.add(dupKey);
        }
        queue.push({ id: nxt, path: cur.path.concat(nxt), msg: outText, sounds, steps });
      }
      order.push(cur.id);
    }

    const copies = results.length;
    return {
      ok: true,
      copies,
      outputs: results.length ? results : [{ target: '(sin salida)', text: text, path: order, sounds: [] }],
      order,
      steps: order.length,
      start: entries[0]?.id,
    };
  }

  // Extrae tokens %...% de un template.
  function tokens(channel) {
    const vars = {};
    if (!channel) return vars;
    const re = /%([a-zA-Z_][a-zA-Z0-9_]*)%/g;
    for (const msg of channel.messages || []) {
      let m;
      while ((m = re.exec(msg))) {
        vars[m[1]] = '';
      }
    }
    return vars;
  }

  // Sonido: Web Audio (beep determinista por nombre, sin recursos de red).
  function playSound(name) {
    const ctxClass = window.AudioContext || window.webkitAudioContext;
    if (!ctxClass) {
      return;
    }
    const ctx = Suite.preview._ctx || (Suite.preview._ctx = new ctxClass());
    if (ctx.state === 'suspended') {
      ctx.resume();
    }
    const f = 220 + (hash(name || '') % 36) * 22;
    const o = ctx.createOscillator(),
      g = ctx.createGain();
    o.type = 'sine';
    o.frequency.value = f;
    g.gain.setValueAtTime(0.001, ctx.currentTime);
    g.gain.exponentialRampToValueAtTime(0.22, ctx.currentTime + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.6);
    o.connect(g);
    g.connect(ctx.destination);
    o.start();
    o.stop(ctx.currentTime + 0.62);
  }
  function hash(s) {
    let h = 0;
    for (let i = 0; i < s.length; i++) {
      h = (h * 31 + s.charCodeAt(i)) >>> 0;
    }
    return h % 7 === 0 ? 1 : h;
  }

  global.Suite = global.Suite || {};
  global.Suite.preview = { renderMini, simulate, tokens, playSound, color: name => COLORS[name] };
})(window || this);

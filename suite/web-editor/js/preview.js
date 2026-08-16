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
    strikethrough: '<s>',
    obfuscated: '',
    reset: '',
    rainbow: '',
    gradient: '',
  };
  function esc(s) {
    return String(s).replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c]);
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
    if (tag === 'tr') {
      return escaped + '<span title="traducible" style="border-bottom:1px dotted currentColor">';
    }
    if (tag === 't') {
      return escaped + '<span title="tag">';
    }
    return escaped;
  }

  // Define los placeholders disponibles en plantillas.
  function tokens(channel) {
    return {
      player_name: 'Steve',
      content: 'hola mundo',
      ct_messages: 'hola mundo',
      lang_source: channel ? channel['lang-source'] || 'auto' : 'auto',
      lang_target: channel ? channel['lang-target'] || 'auto' : 'auto',
    };
  }

  // Simula el pipeline: entra un mensaje en `channel`, recorre el grafo.
  function simulate(state, channelName, text) {
    const nodes = state.graph.nodes || [];
    const edges = state.graph.edges || [];
    const maxSteps = (state.graph.guard && state.graph.guard['max-steps']) || 512;
    const dedup = (state.graph.filter && state.graph.filter['dedup-fanout']) !== false;
    const adj = new Map(nodes.map(n => [n.id, []]));
    for (const e of edges) {
      if (adj.has(e.from)) {
        adj.get(e.from).push(e.to);
      }
    }

    const start =
      nodes.find(n => n.kind === 'input' && n.label === channelName) || nodes.filter(n => n.kind === 'input')[0];
    if (!start) {
      return { ok: false, path: [], outputs: [], steps: 0, reason: 'sin nodo de entrada para ' + channelName };
    }

    const results = [];
    const seen = new Set();
    const queue = [{ id: start.id, path: [start.id], msg: text, sounds: [], steps: 0 }];
    const order = [];
    while (queue.length) {
      const cur = queue.shift();
      const node = nodes.find(n => n.id === cur.id);
      if (!node) {
        continue;
      }

      let keep = true;
      // condición = filtro
      if (node.kind === 'cond' && node.matcher) {
        const m = node.matcher;
        if (m.channel && !channelName.includes(m.channel)) {
          keep = false;
        }
        if (m.sender && !text.includes(m.sender)) {
          keep = false;
        }
      }
      if (!keep) {
        continue;
      }

      // transformación
      let outText = cur.msg,
        sounds = cur.sounds;
      for (const t of node.transforms || []) {
        if (t.op === 'rewrite' && t.template && t.template !== undefined) {
          const chan = state.channels[channelName];
          outText = t.template
            .replace(/%player_name%/g, 'Steve')
            .replace(/%ct_messages%/g, text)
            .replace(/%content%/g, text)
            .replace(/%lang_source%/g, chan ? chan['lang-source'] || 'auto' : 'auto')
            .replace(/%lang_target%/g, chan ? chan['lang-target'] || 'auto' : 'auto');
          if (chan && t.template === '@messages0') {
            outText = (chan.messages[0] || text).replace(/%content%/g, text).replace(/%player_name%/g, 'Steve');
          }
        }
        if (t.op === 'sounds') {
          sounds = sounds.slice();
          for (const a of t.add || []) {
            if (!sounds.includes(a)) {
              sounds.push(a);
            }
          }
          for (const r of t.remove || []) {
            sounds = sounds.filter(s => s !== r);
          }
        }
        if (t.op === 'sleep') {
          /* el delay no cambia el mensaje */
        }
      }

      if (node.kind === 'output' || node.kind === 'redirect') {
        const key = (dedup ? node.id : '') + '/' + channelName + '/' + outText;
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
      outputs: results.length
        ? results
        : [{ target: '(sin salida)', text: start ? text : '', path: order, sounds: [] }],
      order,
      steps: order.length,
      start: start.id,
    };
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

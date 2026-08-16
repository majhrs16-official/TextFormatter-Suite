/* model.js — modelo del proyecto (fuente única) + export/import exacto.
 * config.yml + channels/*.yml replican el schema del host ConfigLoader.
 * rules.yml / translators / sync / manifest = schema v2.2 del editor. */
(function (global) {
  'use strict';

  const KNOWN_LANGS = ['auto', 'en', 'es', 'pt', 'de', 'fr', 'it', 'ja', 'ko', 'zh', 'ar', 'ru'];
  const CHANNEL_DEFAULTS = {
    permission: null,
    'send-permission': null,
    'receive-permission': null,
    'show-sender': true,
    'rate-limit-per-second': 0,
    'lang-source': 'auto',
    'lang-target': 'auto',
    messages: [],
    tooltips: [],
    sounds: [],
  };

  function clone(v) {
    return v === undefined ? undefined : JSON.parse(JSON.stringify(v));
  }
  function ch(name, over) {
    return Object.assign({ name }, clone(CHANNEL_DEFAULTS), clone(over || {}));
  }

  function defaults() {
    return {
      config: {
        'quick-look': true,
        general: { language: 'en' },
        iflow: { engine: { parallel: false }, guard: { 'max-steps': 512 }, filter: { 'dedup-fanout': true } },
        sonido: { enabled: true },
      },
      channels: {
        'chat.global': ch('chat.global', {
          permission: 'cht.chat.global',
          'send-permission': 'cht.chat.global.send',
          'receive-permission': 'cht.chat.global.receive',
          messages: ['&7👉 &f%player_name%&7: %content%', '<green>💬 %content%</green>'],
          tooltips: ['Hover: %lang_source% → %lang_target%'],
          sounds: [{ name: 'entity.experience_orb.pickup', volume: 1.0, pitch: 1.0 }],
        }),
        'chat.hub': ch('chat.hub', {
          permission: 'cht.chat.hub',
          messages: ['<gold>⛨</gold> %content%'],
        }),
        'staff.alert': ch('staff.alert', {
          permission: 'cht.staff.alert',
          'show-sender': false,
          messages: ['<red>⚠ %content%</red>'],
          sounds: [{ name: 'block.note_block.pling', volume: 0.8, pitch: 1.2 }],
        }),
        'vip.chat': ch('vip.chat', {
          permission: 'cht.vip.chat',
          'lang-source': 'es',
          messages: ['<purple>✦ %player_name%: %content%</purple>'],
        }),
      },
      graph: {
        guard: { 'max-steps': 512 },
        filter: { 'dedup-fanout': true },
        nodes: [
          { id: 'n_chat.global', kind: 'input', label: 'chat.global', x: 60, y: 110, w: 150 },
          {
            id: 'n_cond',
            kind: 'cond',
            label: 'filtro anti-silencio',
            matcher: { channel: 'chat.global' },
            x: 340,
            y: 70,
            w: 150,
          },
          {
            id: 'n_transform',
            kind: 'transform',
            label: 'reformatear evento',
            transforms: [
              { op: 'rewrite', template: '<green>💬 %content%</green>' },
              { op: 'sounds', add: ['entity.experience_orb.pickup'], remove: ['block.note_block.pling'] },
            ],
            x: 660,
            y: 40,
            w: 160,
          },
          { id: 'n_loop', kind: 'loop', label: 'bucle re-intento', x: 660, y: 280, w: 140 },
          {
            id: 'n_sleep',
            kind: 'sleep',
            label: 'delay',
            transforms: [{ op: 'sleep', millis: 1500 }],
            x: 1000,
            y: 60,
            w: 130,
          },
          { id: 'n_clean', kind: 'output', label: 'chat.hub', x: 1000, y: 250, w: 130 },
          {
            id: 'n_redirect',
            kind: 'redirect',
            label: 'redirigir a staff',
            target: { channel: 'staff.alert' },
            x: 340,
            y: 330,
            w: 150,
          },
        ],
        edges: [
          { from: 'n_chat.global', to: 'n_cond' },
          { from: 'n_cond', to: 'n_transform' },
          { from: 'n_transform', to: 'n_sleep' },
          { from: 'n_sleep', to: 'n_clean' },
          { from: 'n_transform', to: 'n_loop' },
          { from: 'n_loop', to: 'n_cond' },
          { from: 'n_cond', to: 'n_redirect' },
        ],
      },
      translators: {
        google: { provider: 'google', active: true, pool: { 'max-concurrent': 6 } },
        libre: { provider: 'libre', active: false, 'base-url': '', 'api-key': '', pool: { 'max-concurrent': 6 } },
      },
      sync: {
        discord: { enabled: false, token: '', channel: 0, intents: ['GUILD_MESSAGES', 'MESSAGE_CONTENT'] },
        telegram: { enabled: false, token: '', 'chat-id': 0, hub: '' },
        http: { enabled: false, 'webhook-url': '', 'inbound-port': 0, path: '' },
        'tcp-udp': { enabled: false, protocol: 'TCP', host: '0.0.0.0', 'outbound-port': 0, 'inbound-port': 0 },
        velocity: { enabled: false, secret: '', servers: [], mapping: '* → chat.hub' },
      },
      perms: {
        roles: ['owner', 'admin', 'moderator', 'guard', 'player', 'guest'],
        cols: ['send', 'receive', 'bypass-rate', 'mute', 'broadcast', 'ctr.*', 'admin'],
        matrix: {
          owner: [1, 1, 1, 1, 1, 1, 1],
          admin: [1, 1, 1, 1, 1, 1, 0],
          moderator: [1, 1, 1, 1, 0, 0, 0],
          guard: [1, 1, 0, 1, 0, 0, 0],
          player: [1, 1, 0, 0, 0, 0, 0],
          guest: [0, 1, 0, 0, 0, 0, 0],
        },
      },
      extra: {}, // archivos no conocidos (se conservan en round-trip)
    };
  }

  /* ── EXPORT ────────────────────────────────────────────── */
  function exportFiles(state, validation) {
    const files = {};
    files['config.yml'] = Suite.yaml.stringify(state.config);

    const sortedChannels = Object.keys(state.channels).sort();
    for (const name of sortedChannels) {
      files['channels/' + name + '.yml'] = Suite.yaml.stringify(state.channels[name]);
    }

    files['rules.yml'] = Suite.yaml.stringify(state.graph);
    files['manifest.json'] = JSON.stringify(manifest(state, validation), null, 2);

    for (const [key, edge] of Object.entries(state.sync)) {
      files['sync/' + key + '.yml'] = Suite.yaml.stringify(edge);
    }
    ftrans(state.translators, files);

    for (const [path, text] of Object.entries(state.extra || {})) {
      files[path] = text;
    }
    return files;
  }
  function ftrans(translators, files) {
    for (const [key, cfg] of Object.entries(translators)) {
      files['translators/' + key + '.yml'] = Suite.yaml.stringify(cfg);
    }
  }
  function manifest(state, validation) {
    validation = validation || { errors: 0, warnings: 0, blocking: false, issues: [] };
    const hasTransform = state.graph.nodes.some(n => n.kind === 'transform');
    return {
      schema: 'v2.2',
      'suite-version': '2.1.0',
      'generated-at': new Date().toISOString(),
      capabilities: { transforms: hasTransform },
      validation: {
        errors: validation.errors,
        warnings: validation.warnings,
        blocking: validation.blocking,
        issues: validation.issues,
      },
    };
  }

  /* ── IMPORT ────────────────────────────────────────────── */
  function importFromFiles(state, files) {
    // files: {path → text}
    const next = defaults();
    if (files['config.yml']) {
      const parsed = Suite.yaml.parse(files['config.yml']);
      if (parsed) {
        next.config = deepMerge(next.config, parsed);
      }
    }
    for (const [path, text] of Object.entries(files)) {
      const m = /^channels\/(.+)\.yml$/.exec(path);
      if (m) {
        const parsed = Suite.yaml.parse(text);
        if (parsed && typeof parsed === 'object' && parsed.name) {
          next.channels[parsed.name] = deepMerge(ch(parsed.name), parsed);
        }
      }
    }
    if (files['rules.yml']) {
      const parsed = Suite.yaml.parse(files['rules.yml']);
      if (parsed) {
        next.graph = deepMerge(next.graph, parsed);
      }
    }
    for (const [key] of Object.entries(next.translators)) {
      if (files['translators/' + key + '.yml']) {
        const parsed = Suite.yaml.parse(files['translators/' + key + '.yml']);
        if (parsed) {
          next.translators[key] = deepMerge(parsed, next.translators[key]);
        }
      }
    }
    for (const key of Object.keys(next.sync)) {
      if (files['sync/' + key + '.yml']) {
        const parsed = Suite.yaml.parse(files['sync/' + key + '.yml']);
        if (parsed) {
          next.sync[key] = deepMerge(parsed, next.sync[key]);
        }
      }
    }
    // conserva archivos desconocidos
    next.extra = {};
    for (const [path, text] of Object.entries(files)) {
      if (
        !path.startsWith('channels/') &&
        path !== 'config.yml' &&
        path !== 'rules.yml' &&
        path !== 'manifest.json' &&
        !path.startsWith('translators/') &&
        !path.startsWith('sync/')
      ) {
        next.extra[path] = text;
      }
    }
    return next;
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

  /* ── CRUD ──────────────────────────────────────────────── */
  function addNode(state, kind, label, x, y) {
    const id =
      'n_' +
      (label || kind)
        .toLowerCase()
        .replace(/[\s.]/g, '-')
        .replace(/[^a-z0-9_-]/g, '') +
      '_' +
      Math.random().toString(36).slice(2, 6);
    const node = { id, kind, label: label || kind, x, y, w: 150 };
    if (kind === 'output') {
      node.label = node.label || 'chat.hub';
    }
    state.graph.nodes.push(node);
    return node;
  }
  function removeNode(state, id) {
    state.graph.nodes = state.graph.nodes.filter(n => n.id !== id);
    state.graph.edges = state.graph.edges.filter(e => e.from !== id && e.to !== id);
  }
  function addChannel(state, name, seedMessages) {
    const safe = name || 'nuevo.chat';
    let key = safe;
    if (state.channels[key]) {
      let i = 1;
      while (state.channels[key + (i === 1 ? '' : i)]) {
        i++;
      }
      void i;
      key = safe + '-' + i;
    }
    state.channels[key] = ch(key, { messages: seedMessages || ['&7👉 &f%player_name%&7: %content%'] });
    // auto regla entrada en iFlow
    addNode(state, 'input', key, 60 + Math.random() * 220, 100 + Math.random() * 220);
    return key;
  }
  function renameChannel(state, oldName, newName) {
    if (oldName === newName || !state.channels[oldName] || state.channels[newName]) {
      return false;
    }
    const cfg = state.channels[oldName];
    delete state.channels[oldName];
    cfg.name = newName;
    state.channels[newName] = cfg;
    for (const n of state.graph.nodes) {
      if ((n.kind === 'input' || n.kind === 'output') && n.label === oldName) {
        n.label = newName;
      }
    }
    for (const edge of Object.values(state.sync)) {
      if (edge && typeof edge === 'object' && edge.channel === oldName) {
        edge.channel = newName;
      }
    }
    return true;
  }
  function addEdge(state, from, to) {
    if (from === to) {
      return;
    }
    const dup = state.graph.edges.some(e => e.from === from && e.to === to);
    if (dup) {
      return;
    }
    state.graph.edges.push({ from, to });
  }
  function removeEdge(state, from, to) {
    state.graph.edges = state.graph.edges.filter(e => !(e.from === from && e.to === to));
  }

  global.Suite = global.Suite || {};
  global.Suite.model = {
    defaults,
    clone,
    exportFiles,
    importFromFiles,
    manifest,
    addNode,
    removeNode,
    addChannel,
    renameChannel,
    addEdge,
    removeEdge,
    KNOWN_LANGS,
  };
})(window || this);

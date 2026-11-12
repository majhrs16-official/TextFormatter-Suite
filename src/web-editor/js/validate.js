/* validate.js — validación global de la suite. Produces issues
 * [{level, group, path, msg}] y bloquea descarga con errores. */
(function (global) {
  'use strict';

  // Mapeo de paths a secciones de validación
  const PATH_GROUPS = {
    config: ['config'],
    'config.general': ['config'],
    'config.general.language': ['config'],
    'config.iflow': ['config'],
    'config.sonido': ['config'],
    channels: ['channels'],
    graph: ['graph', 'cycles'],
    'graph.nodes': ['graph', 'cycles'],
    'graph.edges': ['graph', 'cycles'],
    translators: ['translators'],
    sync: ['sync'],
    perms: [], // perms no tiene validación específica
  };

  function getValidationGroups(changedPaths) {
    if (!changedPaths || changedPaths.length === 0) {
      return new Set(['config', 'channels', 'graph', 'cycles', 'translators', 'sync']);
    }
    const groups = new Set();
    for (const p of changedPaths) {
      for (const [prefix, groupsArr] of Object.entries(PATH_GROUPS)) {
        if (p === prefix || p.startsWith(prefix + '.')) {
          for (const g of groupsArr) {
            groups.add(g);
          }
        }
      }
    }
    return groups;
  }

  function validate(state, { changedPaths } = {}) {
    const issues = [];
    const err = (g, p, m) => issues.push({ level: 'error', group: g, path: p, msg: m });
    const warn = (g, p, m) => issues.push({ level: 'warning', group: g, path: p, msg: m });

    const groups = getValidationGroups(changedPaths);
    const langs = Suite.model.KNOWN_LANGS;

    const langsOk = v =>
      v === null ||
      v === undefined ||
      (typeof v === 'string' && (langs.includes(v) || /^[a-z]{2}(-[A-Z]{2})?$/.test(v)));

    if (groups.has('config')) {
      if (!langsOk(state.config.general && state.config.general.language)) {
        warn('config', 'general.language', 'Idioma default no reconocido');
      }
      const steps = state.graph.guard && state.graph.guard['max-steps'];
      if (!Number.isInteger(steps) || steps < 1) {
        err('config', 'iflow.guard.max-steps', 'max-steps debe ser entero ≥ 1');
      }
    }

    if (groups.has('channels')) {
      for (const name of Object.keys(state.channels)) {
        const c = state.channels[name];
        if (!name || /[:#&][\s]/.test(String(c.name || ''))) {
          warn('channels', name, 'Name contiene caracteres raros');
        }
        if (c.permission && !/^[a-z0-9_.-]+$/.test(c.permission)) {
          warn('channels', name, 'permiso base no respeta `cht.*`');
        }
        if (c['send-permission'] && !/^[a-z0-9_.-]+$/.test(c['send-permission'])) {
          warn('channels', name, 'send-permission inválido');
        }
        if (c['receive-permission'] && !/^[a-z0-9_.-]+$/.test(c['receive-permission'])) {
          warn('channels', name, 'receive-permission inválido');
        }
        if (!Array.isArray(c.messages) || c.messages.length === 0) {
          warn('channels', name, 'Sin mensajes (plantilla vacía)');
        }
        if (c['rate-limit-per-second'] < 0) {
          err('channels', name, 'rate-limit negativo');
        }
        if (!langsOk(c['lang-source'])) {
          warn('channels', name, 'lang-source no reconocido');
        }
        if (!langsOk(c['lang-target'])) {
          warn('channels', name, 'lang-target no reconocido');
        }
      }
    }

    if (groups.has('graph')) {
      const nodes = state.graph.nodes || [];
      const ids = new Set();
      for (const n of nodes) {
        if (!n.id) {
          err('iflow', 'nodes', 'Hay un nodo sin id');
        }
        if (ids.has(n.id)) {
          err('iflow', 'nodes/' + n.id, 'id duplicado: ' + n.id);
        }
        ids.add(n.id);
        if (n.kind === 'cond' && (!n.matcher || Object.keys(n.matcher).length === 0)) {
          warn('iflow', 'nodes/' + n.id, 'Nodo cond sin matcher (siempre pasa)');
        }
        if (n.kind === 'transform') {
          warn('iflow', 'nodes/' + n.id, 'transform requiere un motor con capability transforms');
        }
        if (n.kind === 'redirect' && !n.target) {
          warn('iflow', 'nodes/' + n.id, 'Redirección sin target');
        }
      }
      for (const e of state.graph.edges || []) {
        if (!ids.has(e.from)) {
          err('iflow', 'edges', 'arista con origen inexistente: ' + e.from);
        }
        if (!ids.has(e.to)) {
          err('iflow', 'edges', 'arista con destino inexistente: ' + e.to);
        }
      }
    }

    if (groups.has('cycles')) {
      cycleChecks(state, warn);
    }

    if (groups.has('translators')) {
      const google = state.translators.google,
        libre = state.translators.libre;
      if (google && google.active && libre && libre.active) {
        warn('translators', 'providers', 'dos providers activos; se usa la preferencia');
      }
      if (libre && libre.active && !libre['base-url']) {
        err('translators', 'libre.base-url', 'Libre activo sin base-url');
      }
      if (google && google.pool && google.pool['max-concurrent'] < 1) {
        err('translators', 'google.pool', 'pool < 1');
      }
      if (libre && libre.pool && libre.pool['max-concurrent'] < 1) {
        err('translators', 'libre.pool', 'pool < 1');
      }
    }

    if (groups.has('sync')) {
      const s = state.sync;
      if (s) {
        if (s.discord && s.discord.enabled && !s.discord.token) {
          err('sync', 'discord.token', 'Discord habilitado sin token');
        }
        if (s.discord && s.discord.enabled && !(s.discord.channel > 0)) {
          warn('sync', 'discord.channel', 'canal remoto sin id');
        }
        if (s.telegram && s.telegram.enabled && !s.telegram.token) {
          err('sync', 'telegram.token', 'Telegram habilitado sin token');
        }
        if (s.telegram && s.telegram.enabled && !s.telegram.chatId) {
          warn('sync', 'telegram.chatId', 'Telegram habilitado sin chatId');
        }
        if (s.http && s.http.enabled && !s.http.outboundUrl) {
          err('sync', 'http.outboundUrl', 'HTTP habilitado sin outboundUrl');
        }
        if (s.http && s.http.enabled && !(s.http.inboundPort >= 0 && s.http.inboundPort <= 65535)) {
          err('sync', 'http.inboundPort', 'puerto inbound fuera de rango');
        }
        for (const proto of ['tcp', 'udp']) {
          const socket = s[proto];
          if (!socket) {
            continue;
          }
          if (socket.enabled && !socket.remoteHost) {
            err('sync', proto + '.remoteHost', proto.toUpperCase() + ' habilitado sin host remoto');
          }
          if (socket.enabled && !(socket.remotePort > 0 && socket.remotePort <= 65535)) {
            err('sync', proto + '.remotePort', proto.toUpperCase() + ' habilitado sin puerto remoto válido');
          }
          if (!(socket.localPort >= 0 && socket.localPort <= 65535)) {
            err('sync', proto + '.localPort', 'puerto local fuera de rango');
          }
        }
        if (s.velocity && s.velocity.enabled && !s.velocity.secret) {
          warn('sync', 'velocity.secret', 'Velocity habilitado sin secret');
        }
      }
    }

    const errors = issues.filter(i => i.level === 'error').length;
    const warnings = issues.filter(i => i.level === 'warning').length;
    return { issues, errors, warnings, blocking: errors > 0 };
  }

  function cycleChecks(state, warn) {
    const nodes = state.graph.nodes || [];
    const adj = new Map(nodes.map(n => [n.id, []]));
    for (const e of state.graph.edges || []) {
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
        if (!nodes.find(n => n.id === v)) {
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
            const n = nodes.find(x => x.id === id);
            return n && (n.kind === 'cond' || n.kind === 'sleep');
          });
          if (!guard) {
            warn('iflow', 'edges', 'Ciclo sin condición/sleep: ' + cycle.join(' → '));
          }
          return true;
        }
      }
      stack.pop();
      return false;
    }
    for (const n of state.graph.nodes || []) {
      if (!visited.has(n.id)) {
        dfs(n.id);
      }
    }
    void adj;
  }

  global.Suite = global.Suite || {};
  global.Suite.validate = { validate };
})(window || this);

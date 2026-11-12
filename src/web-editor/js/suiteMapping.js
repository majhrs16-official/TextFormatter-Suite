/* suiteMapping.js — mapeo entre el modelo visual y los archivos de la suite.
 *
 * Hidrata las secciones conocidas del modelo desde las rutas canónicas de
 * `storage.md` y vuelca solo lo que cambió. Un archivo presente y no editado
 * conserva sus bytes originales; un archivo opaco (YAML inválido, binario o
 * una sección aún no modelada) nunca se sobrescribe. No crea el manifest del
 * editor ni transporta JARs, caché, journal, backups o temporales. */
(function (global) {
  'use strict';

  const CONFIG_VERSION_LINE = 'config-version: 1.0\n';

  const FIXED = Object.freeze({
    config: 'config/suite/config.yml',
    graph: 'config/iflow-firewall/rules.yml',
    packages: 'manager/library/status',
    'rom.iflow-vm': 'config/iflow-vm/policy.flow',
    'rom.iflow-wasm': 'config/iflow-wasm/policy.wasm',
    'rom.iflow-wasm.sha256': 'config/iflow-wasm/policy.sha256',
  });

  const SYNC_FILES = Object.freeze({
    discord: 'config/sync-discord/config.yml',
    telegram: 'config/sync-telegram/config.yml',
    http: 'config/sync-http/config.yml',
    websocket: 'config/sync-websocket/config.yml',
    tcp: 'config/sync-socket/tcp.yml',
    udp: 'config/sync-socket/udp.yml',
    velocity: 'config/sync-velocity-core/config.yml',
    bukkit: 'config/sync-bukkit/config.yml',
    fabric: 'config/sync-fabric/config.yml',
  });

  const CHANNEL_DIR = 'config/textformatter/channels/';
  const TRANSLATOR_DIR = 'config/chattranslator/translators/';
  const DEFERRED = [FIXED.packages, FIXED['rom.iflow-vm'], FIXED['rom.iflow-wasm'], FIXED['rom.iflow-wasm.sha256']];

  class SuiteMappingError extends Error {
    constructor(message) {
      super(message);
      this.name = 'SuiteMappingError';
    }
  }

  function channelPath(name) {
    return CHANNEL_DIR + name + '.yml';
  }

  function translatorPath(id) {
    return TRANSLATOR_DIR + id + '.yml';
  }

  function syncPath(key) {
    return SYNC_FILES[key] || null;
  }

  function clone(value) {
    return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
  }

  function canonical(value) {
    if (value === undefined) {
      return 'null';
    }
    if (value === null || typeof value !== 'object') {
      return JSON.stringify(value);
    }
    if (Array.isArray(value)) {
      return '[' + value.map(canonical).join(',') + ']';
    }
    const keys = Object.keys(value).sort();
    return '{' + keys.map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}';
  }

  function stripMeta(value) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      return clone(value);
    }
    const out = {};
    for (const [key, item] of Object.entries(value)) {
      if (key === 'config-version') {
        continue;
      }
      out[key] = clone(item);
    }
    return out;
  }

  function serializeYaml(value) {
    const clean = stripMeta(value);
    if (clean && typeof clean === 'object' && !Array.isArray(clean) && Object.keys(clean).length === 0) {
      return CONFIG_VERSION_LINE;
    }
    let text = global.Suite.yaml.stringify(clean);
    if (!text.endsWith('\n')) {
      text += '\n';
    }
    if (clean && typeof clean === 'object' && !Array.isArray(clean)) {
      text += CONFIG_VERSION_LINE;
    }
    return text;
  }

  function decodeText(bytes) {
    if (!bytes) {
      return null;
    }
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
      return text.indexOf('\u0000') === -1 ? text : null;
    } catch (error) {
      return null;
    }
  }

  function readText(archive, path, warnings) {
    const text = decodeText(archive.get(path));
    if (text === null) {
      warnings.push({ path, message: 'el archivo no es texto UTF-8; se conserva sin hidratar' });
    }
    return text;
  }

  function parseMap(text, path, warnings) {
    let parsed = null;
    try {
      parsed = global.Suite.yaml.parse(text);
    } catch (error) {
      warnings.push({ path, message: 'YAML inválido: ' + error.message });
      return null;
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      warnings.push({ path, message: 'la raíz no es un mapa YAML' });
      return null;
    }
    return parsed;
  }

  function isGraph(value) {
    return (
      value &&
      typeof value === 'object' &&
      !Array.isArray(value) &&
      ('nodes' in value || 'edges' in value || 'guard' in value || 'filter' in value)
    );
  }

  function initialSnapshot(state) {
    const snapshot = {
      config: canonical(state.config),
      graph: canonical(state.graph),
      channels: {},
      translators: {},
      sync: {},
    };
    for (const key of Object.keys(state.channels || {})) {
      snapshot.channels[key] = canonical(state.channels[key]);
    }
    for (const key of Object.keys(state.translators || {})) {
      snapshot.translators[key] = canonical(state.translators[key]);
    }
    for (const key of Object.keys(state.sync || {})) {
      snapshot.sync[key] = canonical(state.sync[key]);
    }
    return snapshot;
  }

  /* ── IMPORT ─────────────────────────────────────────────── */
  function fromSuite(archive) {
    if (!archive || typeof archive.paths !== 'function' || typeof archive.get !== 'function') {
      throw new SuiteMappingError('se requiere un SuiteArchive para hidratar el modelo');
    }
    const model = global.Suite.model;
    const state = model.defaults();
    const warnings = [];
    const consumed = new Set();
    const opaque = new Set();
    const deferred = new Set();
    const baselines = { config: null, graph: null, channels: {}, translators: {}, sync: {}, extra: {} };
    const sources = { config: null, graph: null, channels: {}, translators: {}, sync: {}, extra: {} };
    const initial = initialSnapshot(state);

    // config/suite/config.yml
    if (archive.has(FIXED.config)) {
      const text = readText(archive, FIXED.config, warnings);
      const parsed = text === null ? null : parseMap(text, FIXED.config, warnings);
      if (parsed) {
        state.config = model.deepMerge(state.config, stripMeta(parsed));
        baselines.config = canonical(state.config);
        sources.config = FIXED.config;
        consumed.add(FIXED.config);
      } else {
        opaque.add(FIXED.config);
      }
    }

    // config/iflow-firewall/rules.yml
    if (archive.has(FIXED.graph)) {
      const text = readText(archive, FIXED.graph, warnings);
      let parsed = null;
      if (text !== null) {
        try {
          parsed = global.Suite.yaml.parse(text);
        } catch (error) {
          warnings.push({ path: FIXED.graph, message: 'YAML inválido: ' + error.message });
        }
      }
      if (isGraph(parsed)) {
        state.graph = model.deepMerge(state.graph, stripMeta(parsed));
        baselines.graph = canonical(state.graph);
        sources.graph = FIXED.graph;
        consumed.add(FIXED.graph);
      } else {
        if (Array.isArray(parsed)) {
          warnings.push({
            path: FIXED.graph,
            message: 'reglas SpEL ejecutables; se conservan sin hidratar el grafo del editor',
          });
        } else if (parsed) {
          warnings.push({ path: FIXED.graph, message: 'no parece un grafo del editor; se conserva sin hidratar' });
        }
        opaque.add(FIXED.graph);
      }
    }

    // config/textformatter/channels/*.yml
    for (const path of archive.paths()) {
      const match = /^config\/textformatter\/channels\/([^/]+)\.yml$/.exec(path);
      if (!match) {
        continue;
      }
      const key = match[1];
      const text = readText(archive, path, warnings);
      const parsed = text === null ? null : parseMap(text, path, warnings);
      if (!parsed) {
        opaque.add(path);
        continue;
      }
      if (parsed.name && parsed.name !== key) {
        warnings.push({ path, message: 'name "' + parsed.name + '" no coincide con el archivo "' + key + '"' });
      }
      const merged = model.deepMerge(model.channel(key), stripMeta(parsed));
      merged.name = parsed.name || key;
      state.channels[key] = merged;
      baselines.channels[key] = canonical(merged);
      sources.channels[key] = path;
      consumed.add(path);
    }

    // config/chattranslator/translators/*.yml
    for (const path of archive.paths()) {
      const match = /^config\/chattranslator\/translators\/([^/]+)\.yml$/.exec(path);
      if (!match) {
        continue;
      }
      const id = match[1];
      const text = readText(archive, path, warnings);
      const parsed = text === null ? null : parseMap(text, path, warnings);
      if (!parsed) {
        opaque.add(path);
        continue;
      }
      state.translators[id] = model.deepMerge(state.translators[id] || {}, stripMeta(parsed));
      baselines.translators[id] = canonical(state.translators[id]);
      sources.translators[id] = path;
      consumed.add(path);
    }

    // config/sync-*/config.yml y config/sync-socket/{tcp,udp}.yml
    for (const [key, path] of Object.entries(SYNC_FILES)) {
      if (!archive.has(path)) {
        continue;
      }
      const text = readText(archive, path, warnings);
      const parsed = text === null ? null : parseMap(text, path, warnings);
      if (!parsed) {
        opaque.add(path);
        continue;
      }
      state.sync[key] = model.deepMerge(state.sync[key] || {}, stripMeta(parsed));
      baselines.sync[key] = canonical(state.sync[key]);
      sources.sync[key] = path;
      consumed.add(path);
    }

    // Archivos conocidos que se gestionan en pasos posteriores (paquetes y ROM).
    for (const path of DEFERRED) {
      if (archive.has(path)) {
        deferred.add(path);
      }
    }

    // Archivos no mapeados: se conservan como texto opaco en state.extra.
    for (const path of archive.paths()) {
      if (consumed.has(path) || deferred.has(path) || opaque.has(path)) {
        continue;
      }
      const text = readText(archive, path, warnings);
      if (text === null) {
        opaque.add(path);
        continue;
      }
      state.extra[path] = text;
      baselines.extra[path] = text;
      sources.extra[path] = path;
    }

    // Binarios y archivos no representables quedan opacos y se preservan.
    for (const path of archive.paths()) {
      if (!consumed.has(path) && !deferred.has(path) && !(path in state.extra)) {
        opaque.add(path);
      }
    }

    return {
      state,
      initial,
      baselines,
      sources,
      warnings,
      consumed: Array.from(consumed).sort(),
      opaque: Array.from(opaque).sort(),
      deferred: Array.from(deferred).sort(),
    };
  }

  /* ── EXPORT ─────────────────────────────────────────────── */
  function blockedPaths(mapping) {
    const blocked = new Set(mapping && mapping.opaque ? mapping.opaque : []);
    for (const path of mapping && mapping.deferred ? mapping.deferred : []) {
      blocked.add(path);
    }
    return blocked;
  }

  function shouldWrite(current, baseline, source, initialValue, mapping) {
    if (!mapping) {
      return true;
    }
    if (source) {
      return baseline === null || baseline === undefined || canonical(current) !== baseline;
    }
    if (initialValue !== undefined && canonical(current) === initialValue) {
      return false;
    }
    return true;
  }

  function toSuite(state, archive, mapping) {
    if (!state || typeof state !== 'object') {
      throw new SuiteMappingError('se requiere un estado del modelo para exportar');
    }
    const archiveModule = global.Suite.suiteArchive;
    if (!archiveModule) {
      throw new SuiteMappingError('Suite.suiteArchive no está disponible');
    }
    const target = archive || new archiveModule.SuiteArchive(new Map());
    const warnings = [];
    const written = [];
    const preserved = [];
    const removed = [];
    const blocked = blockedPaths(mapping);
    const initial = mapping && mapping.initial ? mapping.initial : null;
    const baselines = (mapping && mapping.baselines) || {};
    const sources = (mapping && mapping.sources) || {};
    const mappedPaths = new Set([FIXED.config, FIXED.graph]);
    for (const key of Object.keys(state.channels || {})) {
      mappedPaths.add(channelPath(key));
    }
    for (const id of Object.keys(state.translators || {})) {
      mappedPaths.add(translatorPath(id));
    }
    for (const key of Object.keys(state.sync || {})) {
      const path = syncPath(key);
      if (path) {
        mappedPaths.add(path);
      }
    }

    function put(path, value, baseline, source, initialValue) {
      if (blocked.has(path)) {
        warnings.push({ path, message: 'archivo opaco o diferido; no se sobrescribe' });
        return;
      }
      if (!shouldWrite(value, baseline, source, initialValue, mapping)) {
        if (target.has(path)) {
          preserved.push(path);
        }
        return;
      }
      target.set(path, new TextEncoder().encode(serializeYaml(value)));
      written.push(path);
    }

    put(FIXED.config, state.config, baselines.config, sources.config, initial ? initial.config : undefined);
    put(FIXED.graph, state.graph, baselines.graph, sources.graph, initial ? initial.graph : undefined);

    const channels = state.channels || {};
    for (const [key, cfg] of Object.entries(channels)) {
      const path = channelPath(key);
      const source = sources.channels ? sources.channels[key] : null;
      put(
        path,
        cfg,
        baselines.channels ? baselines.channels[key] : null,
        source,
        initial && initial.channels ? initial.channels[key] : undefined
      );
      if (source && source !== path) {
        target.delete(source);
        removed.push(source);
      }
    }
    for (const [key, source] of Object.entries(sources.channels || {})) {
      if (!(key in channels)) {
        target.delete(source);
        removed.push(source);
      }
    }

    const translators = state.translators || {};
    for (const [id, cfg] of Object.entries(translators)) {
      const path = translatorPath(id);
      const source = sources.translators ? sources.translators[id] : null;
      put(
        path,
        cfg,
        baselines.translators ? baselines.translators[id] : null,
        source,
        initial && initial.translators ? initial.translators[id] : undefined
      );
      if (source && source !== path) {
        target.delete(source);
        removed.push(source);
      }
    }
    for (const [id, source] of Object.entries(sources.translators || {})) {
      if (!(id in translators)) {
        target.delete(source);
        removed.push(source);
      }
    }

    const sync = state.sync || {};
    for (const [key, cfg] of Object.entries(sync)) {
      const path = syncPath(key);
      if (!path) {
        warnings.push({ path: 'sync.' + key, message: 'sin ruta canónica; se conserva sin exportar' });
        continue;
      }
      const source = sources.sync ? sources.sync[key] : null;
      put(
        path,
        cfg,
        baselines.sync ? baselines.sync[key] : null,
        source,
        initial && initial.sync ? initial.sync[key] : undefined
      );
      if (source && source !== path) {
        target.delete(source);
        removed.push(source);
      }
    }
    for (const [key, source] of Object.entries(sources.sync || {})) {
      if (!(key in sync)) {
        target.delete(source);
        removed.push(source);
      }
    }

    const extra = state.extra || {};
    for (const [path, text] of Object.entries(extra)) {
      if (blocked.has(path)) {
        warnings.push({ path, message: 'archivo opaco o diferido; no se sobrescribe' });
        continue;
      }
      if (mappedPaths.has(path)) {
        warnings.push({ path, message: 'ruta mapeada; no se escribe desde extra' });
        continue;
      }
      const baseline = baselines.extra ? baselines.extra[path] : undefined;
      if (baseline !== undefined && text === baseline && target.has(path)) {
        preserved.push(path);
        continue;
      }
      target.setText(path, text);
      written.push(path);
    }
    for (const path of Object.keys((baselines && baselines.extra) || {})) {
      if (!(path in extra)) {
        target.delete(path);
        removed.push(path);
      }
    }

    function uniqueSorted(items) {
      return Array.from(new Set(items)).sort();
    }

    return {
      archive: target,
      written: uniqueSorted(written),
      preserved: uniqueSorted(preserved),
      removed: uniqueSorted(removed),
      warnings,
    };
  }

  function summarize(archive, mapping) {
    const paths = archive && typeof archive.paths === 'function' ? archive.paths() : [];
    const modules = new Set();
    for (const path of paths) {
      const parts = path.split('/');
      if (parts[0] === 'config' && parts[1]) {
        modules.add(parts[1]);
      } else if (parts[0] === 'manager') {
        modules.add('manager');
      }
    }
    const info =
      archive && typeof archive.summary === 'function' ? archive.summary() : { files: paths.length, bytes: 0 };
    const state = mapping && mapping.state ? mapping.state : null;
    return {
      files: info.files,
      bytes: info.bytes,
      modules: Array.from(modules).sort(),
      mapped: mapping && mapping.consumed ? mapping.consumed.length : 0,
      opaque: mapping && mapping.opaque ? mapping.opaque.length : 0,
      deferred: mapping && mapping.deferred ? mapping.deferred.length : 0,
      extra: state && state.extra ? Object.keys(state.extra).length : 0,
      warnings: mapping && mapping.warnings ? mapping.warnings.length : 0,
    };
  }

  global.Suite = global.Suite || {};
  global.Suite.suiteMapping = {
    SuiteMappingError,
    FIXED,
    SYNC_FILES,
    CHANNEL_DIR,
    TRANSLATOR_DIR,
    DEFERRED,
    channelPath,
    translatorPath,
    syncPath,
    canonical,
    stripMeta,
    serializeYaml,
    summarize,
    fromSuite,
    toSuite,
  };
})(window || this);

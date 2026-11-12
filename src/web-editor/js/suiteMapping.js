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

  /* Módulos que el runtime reconoce bajo `config/`. Un directorio fuera de esta
   * lista es un aviso, no un error: puede ser una extensión válida que el editor
   * todavía no modela. */
  const KNOWN_MODULES = new Set([
    'suite',
    'iflow',
    'iflow-firewall',
    'iflow-vm',
    'iflow-wasm',
    'textformatter',
    'chattranslator',
    'sync-bus',
    'sync-discord',
    'sync-telegram',
    'sync-http',
    'sync-websocket',
    'sync-socket',
    'sync-velocity-core',
    'sync-bukkit',
    'sync-fabric',
    'inworld',
    'observability',
    'presets',
  ]);
  const DEFERRED = [];

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

  function isMapLike(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
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

  function parseYaml(text) {
    if (global.Suite.yamlCst && typeof global.Suite.yamlCst.parseValue === 'function') {
      return global.Suite.yamlCst.parseValue(text);
    }
    return global.Suite.yaml.parse(text);
  }

  function parseMap(text, path, warnings) {
    let parsed = null;
    try {
      parsed = parseYaml(text);
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

  function parsePackages(text, path, warnings) {
    const db = global.Suite.packageDb;
    if (!db || typeof db.parse !== 'function') {
      warnings.push({ path, message: 'Suite.packageDb no está disponible; se conserva sin hidratar' });
      return null;
    }
    try {
      return db.parse(text);
    } catch (error) {
      warnings.push({ path, message: 'base de paquetes inválida: ' + error.message });
      return null;
    }
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
      packages: state.packages === null || state.packages === undefined ? null : canonical(state.packages),
      roms: canonical(state.roms || []),
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
    const baselines = {
      config: null,
      graph: null,
      packages: null,
      rom: {},
      channels: {},
      translators: {},
      sync: {},
      extra: {},
    };
    const sources = { config: null, graph: null, packages: null, channels: {}, translators: {}, sync: {}, extra: {} };
    const texts = {};
    const baselineValues = { config: null, graph: null, packages: null, channels: {}, translators: {}, sync: {} };
    const initial = initialSnapshot(state);

    // config/suite/config.yml
    if (archive.has(FIXED.config)) {
      const text = readText(archive, FIXED.config, warnings);
      const parsed = text === null ? null : parseMap(text, FIXED.config, warnings);
      if (parsed) {
        state.config = model.deepMerge(state.config, stripMeta(parsed));
        baselines.config = canonical(state.config);
        sources.config = FIXED.config;
        texts[FIXED.config] = text;
        baselineValues.config = clone(state.config);
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
          parsed = parseYaml(text);
        } catch (error) {
          warnings.push({ path: FIXED.graph, message: 'YAML inválido: ' + error.message });
        }
      }
      if (isGraph(parsed)) {
        state.graph = model.deepMerge(state.graph, stripMeta(parsed));
        baselines.graph = canonical(state.graph);
        sources.graph = FIXED.graph;
        texts[FIXED.graph] = text;
        baselineValues.graph = clone(state.graph);
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

    // manager/library/status
    if (archive.has(FIXED.packages)) {
      const text = readText(archive, FIXED.packages, warnings);
      const entries = text === null ? null : parsePackages(text, FIXED.packages, warnings);
      if (entries) {
        state.packages = entries;
        baselines.packages = canonical(entries);
        sources.packages = FIXED.packages;
        texts[FIXED.packages] = text;
        baselineValues.packages = clone(entries);
        consumed.add(FIXED.packages);
      } else {
        opaque.add(FIXED.packages);
      }
    }

    // ROM de iFlow: .flow y .wasm bajo sus directorios canónicos. Una ROM
    // inválida queda opaca y se preserva; nunca se hidrata a medias.
    const romModule = global.Suite.rom;
    if (romModule) {
      for (const path of archive.paths()) {
        if (!romModule.isRomPath(path)) {
          continue;
        }
        const bytes = archive.get(path);
        const kind = romModule.kindOfPath(path);
        let sidecarPath = null;
        let sidecarText = null;
        if (kind === 'wasm') {
          for (const candidate of [romModule.sidecarPathFor(path), path + '.sha256']) {
            if (archive.has(candidate)) {
              sidecarPath = candidate;
              sidecarText = readText(archive, candidate, warnings);
              break;
            }
          }
        }
        const entry = romModule.entryFromBytes(path, bytes, {
          sidecarPath,
          sidecarText,
          requireSidecar: kind === 'wasm',
        });
        if (entry.valid) {
          state.roms.push(entry);
          baselines.rom[path] = romModule.toBase64(bytes);
          if (sidecarPath && sidecarText !== null) {
            baselines.rom[sidecarPath] = sidecarText;
            texts[sidecarPath] = sidecarText;
          }
          consumed.add(path);
          if (sidecarPath) {
            consumed.add(sidecarPath);
          }
        } else {
          opaque.add(path);
          warnings.push({ path, message: 'ROM inválida: ' + entry.error });
          if (sidecarPath) {
            opaque.add(sidecarPath);
          }
        }
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
      texts[path] = text;
      baselineValues.channels[key] = clone(merged);
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
      texts[path] = text;
      baselineValues.translators[id] = clone(state.translators[id]);
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
      texts[path] = text;
      baselineValues.sync[key] = clone(state.sync[key]);
      consumed.add(path);
    }

    // Rutas diferidas explícitas. Vacío desde que paquetes y ROM se hidratan;
    // se conserva el contrato para futuras secciones que aún no se modelen.
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
      texts,
      baselineValues,
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
    const writes = new Map();
    const deletes = new Set();
    const blocked = blockedPaths(mapping);
    const initial = mapping && mapping.initial ? mapping.initial : null;
    const baselines = (mapping && mapping.baselines) || {};
    const sources = (mapping && mapping.sources) || {};
    const texts = (mapping && mapping.texts) || {};
    const baselineValues = (mapping && mapping.baselineValues) || {};
    const mappedPaths = new Set([FIXED.config, FIXED.graph, FIXED.packages]);
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
    for (const entry of Array.isArray(state.roms) ? state.roms : []) {
      if (entry && typeof entry.path === 'string') {
        mappedPaths.add(entry.path);
        if (entry.kind === 'wasm' && global.Suite.rom) {
          mappedPaths.add(entry.sidecarPath || global.Suite.rom.sidecarPathFor(entry.path));
        }
      }
    }
    for (const path of Object.keys((mapping && mapping.baselines && mapping.baselines.rom) || {})) {
      mappedPaths.add(path);
    }

    /* El modelo hidratado mezcla defaults con el archivo. El CST solo debe ver
     * las claves que estaban en el archivo más las que el usuario cambió; si no,
     * añadiría defaults ausentes y dejaría de conservar el formato original. */
    function project(fileValue, baselineValue, current) {
      if (!isMapLike(current) || !isMapLike(fileValue)) {
        return current;
      }
      const out = {};
      for (const [key, value] of Object.entries(current)) {
        if (Object.prototype.hasOwnProperty.call(fileValue, key)) {
          out[key] = project(fileValue[key], baselineValue ? baselineValue[key] : undefined, value);
          continue;
        }
        const base =
          baselineValue && Object.prototype.hasOwnProperty.call(baselineValue, key) ? baselineValue[key] : undefined;
        if (canonical(value) !== canonical(base)) {
          out[key] = value;
        }
      }
      return out;
    }

    /* Aplica el delta del modelo sobre el texto original con el CST. Un archivo
     * nuevo se serializa completo; un archivo existente nunca se reescribe si
     * el CST no puede representar la edición sin perder formato. */
    function render(path, value, baselineValue, source) {
      const original = source ? texts[source] : undefined;
      if (original === undefined) {
        return serializeYaml(value);
      }
      const cst = global.Suite.yamlCst;
      if (!cst || typeof cst.apply !== 'function') {
        throw new SuiteMappingError('Suite.yamlCst no está disponible para conservar el formato de ' + path);
      }
      try {
        const fileValue = cst.parseValue(original);
        return cst.apply(original, fileValue, project(fileValue, baselineValue, value), { ensureConfigVersion: true });
      } catch (error) {
        throw new SuiteMappingError('no se puede conservar el formato de ' + path + ': ' + error.message);
      }
    }

    function put(path, value, baseline, baselineValue, source, initialValue) {
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
      writes.set(path, new TextEncoder().encode(render(path, value, baselineValue, source)));
      written.push(path);
    }

    /* La base de paquetes no es YAML: se serializa con `packageDb` y solo si el
     * modelo cambió. Un archivo opaco o ausente no se inventa ni se pisa. */
    function putPackages() {
      const current = state.packages;
      if (current === null || current === undefined) {
        return;
      }
      if (blocked.has(FIXED.packages)) {
        warnings.push({ path: FIXED.packages, message: 'archivo opaco o diferido; no se sobrescribe' });
        return;
      }
      if (
        !shouldWrite(current, baselines.packages, sources.packages, initial ? initial.packages : undefined, mapping)
      ) {
        if (target.has(FIXED.packages)) {
          preserved.push(FIXED.packages);
        }
        return;
      }
      const db = global.Suite.packageDb;
      if (!db || typeof db.serialize !== 'function') {
        throw new SuiteMappingError('Suite.packageDb no está disponible para serializar ' + FIXED.packages);
      }
      try {
        writes.set(FIXED.packages, new TextEncoder().encode(db.serialize(current)));
        written.push(FIXED.packages);
      } catch (error) {
        throw new SuiteMappingError('no se puede serializar ' + FIXED.packages + ': ' + error.message);
      }
    }

    /* Las ROM no son YAML ni se serializan: se validan y se escriben byte a
     * byte. Un .wasm exige que su sidecar declare el SHA-256 real; si no
     * coincide, la exportación falla antes de tocar el ZIP. */
    function putRoms() {
      const entries = Array.isArray(state.roms) ? state.roms : [];
      const baselineRom = baselines.rom || {};
      if (entries.length === 0 && Object.keys(baselineRom).length === 0) {
        return;
      }
      const romModule = global.Suite.rom;
      if (!romModule) {
        throw new SuiteMappingError('Suite.rom no está disponible para exportar las ROM');
      }
      const seen = new Set();
      for (const entry of entries) {
        if (!entry || typeof entry !== 'object' || typeof entry.path !== 'string') {
          throw new SuiteMappingError('entrada de ROM inválida');
        }
        if (seen.has(entry.path)) {
          throw new SuiteMappingError('ruta de ROM duplicada: ' + entry.path);
        }
        seen.add(entry.path);
        const kind = romModule.kindOfPath(entry.path);
        if (!kind) {
          throw new SuiteMappingError('la ruta de ROM no termina en .flow o .wasm: ' + entry.path);
        }
        if (entry.kind && entry.kind !== kind) {
          throw new SuiteMappingError('el tipo declarado de ' + entry.path + ' no coincide con su extensión');
        }
        if (blocked.has(entry.path)) {
          warnings.push({ path: entry.path, message: 'archivo opaco o diferido; no se sobrescribe' });
          continue;
        }
        let bytes;
        try {
          bytes = romModule.fromBase64(entry.data);
        } catch (error) {
          throw new SuiteMappingError('datos base64 inválidos en ' + entry.path + ': ' + error.message);
        }
        const result = romModule.validate(kind, bytes);
        if (!result.ok) {
          throw new SuiteMappingError('ROM inválida ' + entry.path + ': ' + result.error);
        }
        const current = romModule.toBase64(bytes);
        const baseline = baselineRom[entry.path];
        const changed = baseline !== current;
        if (changed || !target.has(entry.path)) {
          writes.set(entry.path, bytes);
          written.push(entry.path);
        } else {
          preserved.push(entry.path);
        }
        if (kind !== 'wasm') {
          continue;
        }

        const hash = romModule.sha256Hex(bytes);
        const sidecarPath = entry.sidecarPath || romModule.sidecarPathFor(entry.path);
        if (seen.has(sidecarPath)) {
          throw new SuiteMappingError('ruta de sidecar duplicada: ' + sidecarPath);
        }
        seen.add(sidecarPath);
        if (blocked.has(sidecarPath)) {
          warnings.push({ path: sidecarPath, message: 'archivo opaco o diferido; no se sobrescribe' });
          continue;
        }
        const declared = romModule.sidecarHash(entry.sidecarText || '') || String(entry.sha256 || '').toLowerCase();
        if (!declared) {
          throw new SuiteMappingError('falta el SHA-256 de ' + entry.path + '; recalcula el hash');
        }
        if (declared !== hash) {
          throw new SuiteMappingError('el SHA-256 de ' + entry.path + ' no coincide; recalcula el hash');
        }
        const baselineSidecar = baselineRom[sidecarPath];
        if (!changed && baselineSidecar !== undefined && target.has(sidecarPath)) {
          preserved.push(sidecarPath);
        } else {
          writes.set(
            sidecarPath,
            new TextEncoder().encode(romModule.formatSidecar(hash, romModule.basename(entry.path)))
          );
          written.push(sidecarPath);
        }
      }

      for (const path of Object.keys(baselineRom)) {
        if (seen.has(path)) {
          continue;
        }
        if (blocked.has(path)) {
          warnings.push({ path, message: 'archivo opaco o diferido; no se sobrescribe' });
          continue;
        }
        deletes.add(path);
        removed.push(path);
      }
    }

    put(
      FIXED.config,
      state.config,
      baselines.config,
      baselineValues.config,
      sources.config,
      initial ? initial.config : undefined
    );
    put(
      FIXED.graph,
      state.graph,
      baselines.graph,
      baselineValues.graph,
      sources.graph,
      initial ? initial.graph : undefined
    );
    putPackages();
    putRoms();

    const channels = state.channels || {};
    for (const [key, cfg] of Object.entries(channels)) {
      const path = channelPath(key);
      const source = sources.channels ? sources.channels[key] : null;
      put(
        path,
        cfg,
        baselines.channels ? baselines.channels[key] : null,
        baselineValues.channels ? baselineValues.channels[key] : null,
        source,
        initial && initial.channels ? initial.channels[key] : undefined
      );
      if (source && source !== path) {
        deletes.add(source);
        removed.push(source);
      }
    }
    for (const [key, source] of Object.entries(sources.channels || {})) {
      if (!(key in channels)) {
        deletes.add(source);
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
        baselineValues.translators ? baselineValues.translators[id] : null,
        source,
        initial && initial.translators ? initial.translators[id] : undefined
      );
      if (source && source !== path) {
        deletes.add(source);
        removed.push(source);
      }
    }
    for (const [id, source] of Object.entries(sources.translators || {})) {
      if (!(id in translators)) {
        deletes.add(source);
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
        baselineValues.sync ? baselineValues.sync[key] : null,
        source,
        initial && initial.sync ? initial.sync[key] : undefined
      );
      if (source && source !== path) {
        deletes.add(source);
        removed.push(source);
      }
    }
    for (const [key, source] of Object.entries(sources.sync || {})) {
      if (!(key in sync)) {
        deletes.add(source);
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
      writes.set(path, new TextEncoder().encode(text));
      written.push(path);
    }
    for (const path of Object.keys((baselines && baselines.extra) || {})) {
      if (!(path in extra)) {
        deletes.add(path);
        removed.push(path);
      }
    }

    /* Ninguna escritura toca el archivo hasta que todos los CST han tenido
     * éxito: una edición no representable deja el ZIP intacto. */
    for (const path of deletes) {
      if (!writes.has(path)) {
        target.delete(path);
      }
    }
    for (const [path, bytes] of writes) {
      target.set(path, bytes);
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

  /* ── VALIDACIÓN ─────────────────────────────────────────── */

  /**
   * Valida el YAML exportado: cada `.yml`/`.yaml` bajo `config/` debe parsear y
   * declarar `config-version: 1.0`. Un módulo desconocido y un archivo opaco
   * preservado son avisos; un archivo que el editor escribe y no cumple el
   * contrato es un error que bloquea la exportación. Las ROM y
   * `manager/library/status` tienen su propio formato y no pasan por aquí.
   */
  function validateSuite(archive, mapping) {
    const errors = [];
    const warnings = [];
    const paths = archive && typeof archive.paths === 'function' ? archive.paths() : [];
    const opaque = new Set(mapping && Array.isArray(mapping.opaque) ? mapping.opaque : []);
    const modules = new Set();
    const reported = new Set();
    const decoder = new TextDecoder('utf-8', { fatal: true });
    for (const path of paths) {
      const moduleMatch = /^config\/([^/]+)\//.exec(path);
      if (moduleMatch) {
        modules.add(moduleMatch[1]);
        if (!KNOWN_MODULES.has(moduleMatch[1]) && !reported.has(moduleMatch[1])) {
          reported.add(moduleMatch[1]);
          warnings.push({ path: 'config/' + moduleMatch[1] + '/', message: 'módulo desconocido' });
        }
      }
      if (!/\.ya?ml$/i.test(path)) {
        continue;
      }
      const bytes = archive.get(path);
      if (!bytes) {
        continue;
      }
      if (opaque.has(path)) {
        warnings.push({ path, message: 'archivo opaco preservado; no se valida' });
        continue;
      }
      let text;
      try {
        text = decoder.decode(bytes);
      } catch (error) {
        errors.push({ path, message: 'no es texto UTF-8' });
        continue;
      }
      let value;
      try {
        value = global.Suite.yamlCst ? global.Suite.yamlCst.parseValue(text) : global.Suite.yaml.parse(text);
      } catch (error) {
        errors.push({ path, message: 'YAML inválido: ' + error.message });
        continue;
      }
      if (!value || typeof value !== 'object' || Array.isArray(value)) {
        errors.push({ path, message: 'la raíz no es un mapa' });
        continue;
      }
      const version = value['config-version'];
      if (version === undefined) {
        errors.push({ path, message: 'falta config-version' });
      } else if (!(version === 1 || version === '1.0')) {
        errors.push({ path, message: 'config-version no es 1.0' });
      }
    }
    return { errors, warnings, modules: Array.from(modules).sort() };
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
    validateSuite,
    summarize,
    fromSuite,
    toSuite,
  };
})(window || this);

/* suiteArchive.js — import/export del conjunto seleccionado de artefactos.
 *
 * No transporta el data folder completo: solo `config/**`, la base de datos de
 * paquetes (`manager/library/status`) y las ROM que viven dentro de `config/`.
 * Los archivos desconocidos bajo `config/` se conservan byte a byte. La
 * exportación siempre usa el prefijo `TextFormatter-Suite/` y un orden
 * determinista; los límites anti-bomba viven en código. */
(function (global) {
  'use strict';

  const PREFIX = 'TextFormatter-Suite/';
  const GARBAGE = /(^|\/)(__MACOSX|\.DS_Store|Thumbs\.db)(\/|$)/i;
  const EXCLUDED_PREFIXES = [
    'manager/library/packages/',
    'manager/library/dependencies/',
    'manager/library/cache/',
    'journal/',
    'backup/',
    'trash/',
    'tmp/',
    'manager/runtime/',
    'logs/',
  ];
  const EXCLUDED_SUFFIXES = ['.jar', '.log', '.tmp'];

  const FALLBACK_LIMITS = Object.freeze({
    maxEntries: 100000,
    maxTotalBytes: 1024 * 1024 * 1024,
    maxEntryBytes: 256 * 1024 * 1024,
  });

  class SuiteArchiveError extends Error {
    constructor(message) {
      super(message);
      this.name = 'SuiteArchiveError';
    }
  }

  function limitsOf(options) {
    const source = (options && options.limits) || options || {};
    const defaults = (global.Suite && global.Suite.zip && global.Suite.zip.DEFAULT_LIMITS) || FALLBACK_LIMITS;
    return Object.assign({}, FALLBACK_LIMITS, defaults, source);
  }

  function normalizePath(raw) {
    if (typeof raw !== 'string' || raw.length === 0) {
      throw new SuiteArchiveError('empty archive path');
    }
    if (raw.indexOf('\0') >= 0) {
      throw new SuiteArchiveError('archive path contains a NUL byte');
    }
    const path = raw.replace(/\\/g, '/');
    if (path.startsWith('/')) {
      throw new SuiteArchiveError("absolute archive path is not allowed: '" + raw + "'");
    }
    if (/^[A-Za-z]:/.test(path)) {
      throw new SuiteArchiveError("drive-qualified archive path is not allowed: '" + raw + "'");
    }
    const parts = path.split('/');
    const normalized = [];
    for (const part of parts) {
      if (part === '' || part === '.') {
        continue;
      }
      if (part === '..') {
        throw new SuiteArchiveError("archive path escapes the root: '" + raw + "'");
      }
      normalized.push(part);
    }
    if (normalized.length === 0) {
      throw new SuiteArchiveError("archive path is empty after normalization: '" + raw + "'");
    }
    return normalized.join('/');
  }

  function isGarbage(path) {
    return GARBAGE.test(path);
  }

  function isExcluded(path) {
    const lower = path.toLowerCase();
    if (EXCLUDED_SUFFIXES.some(suffix => lower.endsWith(suffix))) {
      return true;
    }
    return EXCLUDED_PREFIXES.some(prefix => path === prefix.slice(0, -1) || path.startsWith(prefix));
  }

  function isSuiteFile(path) {
    return path.startsWith('config/') || path === 'manager/library/status';
  }

  function detectRoot(paths) {
    if (paths.length === 0) {
      return '';
    }
    const prefixed = paths.every(path => path === PREFIX.slice(0, -1) || path.startsWith(PREFIX));
    if (prefixed) {
      return PREFIX;
    }
    const atRoot = paths.every(path => {
      const first = path.split('/')[0];
      return first === 'config' || first === 'manager';
    });
    if (atRoot) {
      return '';
    }
    const firsts = new Set(paths.map(path => path.split('/')[0]));
    if (firsts.size === 1) {
      throw new SuiteArchiveError(
        "unexpected archive root '" + [...firsts][0] + "'; expected '" + PREFIX + "' or files at the root"
      );
    }
    throw new SuiteArchiveError('mixed archive root; expected a single ' + PREFIX + ' prefix or files at the root');
  }

  function stripRoot(path, root) {
    if (!root) {
      return path;
    }
    if (path === PREFIX.slice(0, -1)) {
      return '';
    }
    if (!path.startsWith(root)) {
      throw new SuiteArchiveError("archive path '" + path + "' is outside the detected root");
    }
    return path.slice(root.length);
  }

  function readEntry(entry) {
    if (entry && typeof entry.getData === 'function') {
      const data = entry.getData();
      return data instanceof Uint8Array ? data : new Uint8Array(data);
    }
    const data = entry ? entry.data : null;
    if (typeof data === 'string') {
      return new TextEncoder().encode(data);
    }
    return data instanceof Uint8Array ? data : new Uint8Array(data);
  }

  function isDirectoryEntry(entry, rawName) {
    return Boolean(entry && entry.isDirectory) || /[\\/]$/.test(String(rawName));
  }

  class SuiteArchive {
    constructor(files, meta) {
      const info = meta || {};
      this.files = files instanceof Map ? files : new Map();
      this.root = info.root || '';
      this.ignored = Object.assign({ garbage: 0, excluded: 0, outside: 0 }, info.ignored || {});
      this.limits = limitsOf(info);
    }

    paths() {
      return [...this.files.keys()].sort();
    }

    has(path) {
      return this.files.has(normalizePath(path));
    }

    get(path) {
      const data = this.files.get(normalizePath(path));
      return data === undefined ? null : data;
    }

    getText(path) {
      const data = this.get(path);
      return data === null ? null : new TextDecoder('utf-8').decode(data);
    }

    set(path, data) {
      const normalized = normalizePath(path);
      if (isExcluded(normalized)) {
        throw new SuiteArchiveError("path is excluded from the suite archive: '" + normalized + "'");
      }
      if (!isSuiteFile(normalized)) {
        throw new SuiteArchiveError("path is outside the selected suite artifacts: '" + normalized + "'");
      }
      const bytes =
        typeof data === 'string'
          ? new TextEncoder().encode(data)
          : data instanceof Uint8Array
            ? data
            : new Uint8Array(data);
      if (bytes.length > this.limits.maxEntryBytes) {
        throw new SuiteArchiveError(
          "entry '" + normalized + "' exceeds the per-file limit of " + this.limits.maxEntryBytes + ' bytes'
        );
      }
      let total = bytes.length;
      for (const [key, value] of this.files) {
        if (key !== normalized) {
          total += value.length;
        }
      }
      if (total > this.limits.maxTotalBytes) {
        throw new SuiteArchiveError('suite archive exceeds the total limit of ' + this.limits.maxTotalBytes + ' bytes');
      }
      if (!this.files.has(normalized) && this.files.size >= this.limits.maxEntries) {
        throw new SuiteArchiveError('suite archive exceeds the limit of ' + this.limits.maxEntries + ' entries');
      }
      this.files.set(normalized, bytes);
      return this;
    }

    setText(path, text) {
      return this.set(path, new TextEncoder().encode(text));
    }

    delete(path) {
      return this.files.delete(normalizePath(path));
    }

    summary() {
      let bytes = 0;
      for (const data of this.files.values()) {
        bytes += data.length;
      }
      return {
        files: this.files.size,
        bytes,
        root: this.root,
        ignored: Object.assign({}, this.ignored),
      };
    }

    clone() {
      return new SuiteArchive(new Map(this.files), {
        root: this.root,
        ignored: this.ignored,
        limits: this.limits,
      });
    }

    toEntries(options) {
      const limits = limitsOf(options || { limits: this.limits });
      const paths = this.paths();
      if (paths.length > limits.maxEntries) {
        throw new SuiteArchiveError('suite archive exceeds the limit of ' + limits.maxEntries + ' entries');
      }
      let total = 0;
      const entries = [];
      for (const path of paths) {
        if (!isSuiteFile(path) || isExcluded(path)) {
          continue;
        }
        const data = this.files.get(path);
        if (data.length > limits.maxEntryBytes) {
          throw new SuiteArchiveError(
            "entry '" + path + "' exceeds the per-file limit of " + limits.maxEntryBytes + ' bytes'
          );
        }
        total += data.length;
        if (total > limits.maxTotalBytes) {
          throw new SuiteArchiveError('suite archive exceeds the total limit of ' + limits.maxTotalBytes + ' bytes');
        }
        entries.push({ name: PREFIX + path, data });
      }
      return entries;
    }

    toZip(options) {
      if (!global.Suite || !global.Suite.zip || typeof global.Suite.zip.build !== 'function') {
        throw new SuiteArchiveError('Suite.zip.build is not available');
      }
      return global.Suite.zip.build(this.toEntries(options));
    }

    static fromEntries(entries, options) {
      return fromEntries(entries, options);
    }

    static fromReader(reader, options) {
      return fromReader(reader, options);
    }

    static fromZip(bytes, options) {
      return fromZip(bytes, options);
    }
  }

  function fromReader(reader, options) {
    if (!reader) {
      throw new SuiteArchiveError('a ZIP reader is required');
    }
    return fromEntries(reader.entries || reader, options);
  }

  function fromZip(bytes, options) {
    if (!global.Suite || !global.Suite.zip || typeof global.Suite.zip.Reader !== 'function') {
      throw new SuiteArchiveError('Suite.zip.Reader is not available');
    }
    return fromEntries(new global.Suite.zip.Reader(bytes, options).entries, options);
  }

  function fromEntries(entries, options) {
    const limits = limitsOf(options);
    const candidates = [];
    const ignored = { garbage: 0, excluded: 0, outside: 0 };

    for (const entry of entries || []) {
      const rawName = entry && entry.filename !== undefined ? entry.filename : entry && entry.name;
      if (isDirectoryEntry(entry, rawName)) {
        continue;
      }
      const path = normalizePath(rawName);
      if (isGarbage(path)) {
        ignored.garbage++;
        continue;
      }
      if (entry && entry.isSymlink) {
        throw new SuiteArchiveError("symlink archive entries are not allowed: '" + path + "'");
      }
      candidates.push({ entry, path });
    }

    const root = detectRoot(candidates.map(candidate => candidate.path));
    const files = new Map();
    let totalBytes = 0;

    for (const candidate of candidates) {
      const path = stripRoot(candidate.path, root);
      if (!path) {
        continue;
      }
      if (path === PREFIX.slice(0, -1) || path.startsWith(PREFIX)) {
        throw new SuiteArchiveError("nested '" + PREFIX + "' root is not allowed");
      }
      if (isExcluded(path)) {
        ignored.excluded++;
        continue;
      }
      if (!isSuiteFile(path)) {
        ignored.outside++;
        continue;
      }
      if (files.has(path)) {
        throw new SuiteArchiveError("duplicate archive path after normalization: '" + path + "'");
      }
      const data = readEntry(candidate.entry);
      if (data.length > limits.maxEntryBytes) {
        throw new SuiteArchiveError(
          "entry '" + path + "' exceeds the per-file limit of " + limits.maxEntryBytes + ' bytes'
        );
      }
      totalBytes += data.length;
      if (totalBytes > limits.maxTotalBytes) {
        throw new SuiteArchiveError('suite archive exceeds the total limit of ' + limits.maxTotalBytes + ' bytes');
      }
      files.set(path, data);
    }

    if (files.size > limits.maxEntries) {
      throw new SuiteArchiveError('suite archive exceeds the limit of ' + limits.maxEntries + ' entries');
    }
    return new SuiteArchive(files, { root, ignored, limits });
  }

  global.Suite = global.Suite || {};
  global.Suite.suiteArchive = {
    SuiteArchive,
    SuiteArchiveError,
    PREFIX,
    normalizePath,
    detectRoot,
    isGarbage,
    isExcluded,
    isSuiteFile,
    fromEntries,
    fromReader,
    fromZip,
  };
})(window || this);

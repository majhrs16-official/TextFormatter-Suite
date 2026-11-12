/* packageDb.js — lector y escritor de `manager/library/status`.
 *
 * La base de paquetes NO es YAML: usa el formato de bloques de
 * `ManifestParser` (manager-spi). Cada entrada tiene campos `Clave: valor` y
 * una sección `Files:` con líneas `ruta<TAB>tamaño<TAB>sha256<TAB>dueño`,
 * separadas por una línea en blanco. El lector es estricto: una línea que el
 * parser del manager ignoraría (comentario, texto suelto) se rechaza para no
 * perderla al reescribir. El escritor reproduce el formato canónico del
 * manager y conserva los campos desconocidos y los archivos. */
(function (global) {
  'use strict';

  const TYPES = Object.freeze(['PACKAGE', 'DEPENDENCY', 'METAPACKAGE']);
  const KNOWN_FIELDS = Object.freeze([
    'Name',
    'Version',
    'Channel',
    'Type',
    'Dependencies',
    'Exclude',
    'Provide',
    'Arch',
    'Platform',
    'URL',
    'OS',
    'SHA256',
    'Size',
    'SourceArtifact',
    'ParentPackage',
    'Files',
  ]);
  const KNOWN = new Set(KNOWN_FIELDS);
  const SHA256 = /^[a-fA-F0-9]{64}$/;

  class PackageDbError extends Error {
    constructor(message) {
      super(message);
      this.name = 'PackageDbError';
    }
  }

  function fail(message) {
    throw new PackageDbError(message);
  }

  function isMapLike(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }

  function text(value) {
    return value === undefined || value === null ? '' : String(value);
  }

  function strip(value) {
    return text(value).trim();
  }

  function normalizeSource(source) {
    if (typeof source !== 'string') {
      fail('la base de paquetes debe ser texto');
    }
    if (source.indexOf('\u0000') !== -1) {
      fail('la base de paquetes contiene un byte NUL');
    }
    if (/\r(?!\n)/.test(source)) {
      fail('la base de paquetes contiene un CR aislado');
    }
    return source.replace(/\r\n/g, '\n');
  }

  function parseLong(raw, what, line) {
    const value = strip(raw);
    if (!/^\+?\d+$/.test(value)) {
      fail(what + ' no es un entero no negativo' + (line ? ' (línea ' + line + ')' : '') + ': ' + value);
    }
    const number = Number(value);
    if (!Number.isSafeInteger(number)) {
      fail(what + ' excede el rango seguro' + (line ? ' (línea ' + line + ')' : ''));
    }
    return number;
  }

  /* Misma normalización que `ManifestParser.DependencySpec`: `name`,
   * `name@constraint` o `name constraint`; `*` no se serializa. */
  function normalizeDependency(raw) {
    const trimmed = strip(raw);
    if (trimmed === '') {
      return null;
    }
    const at = trimmed.indexOf('@');
    if (at > 0) {
      const name = strip(trimmed.slice(0, at));
      const constraint = strip(trimmed.slice(at + 1)) || '*';
      return constraint === '*' ? name : name + '@' + constraint;
    }
    const space = trimmed.indexOf(' ');
    if (space > 0) {
      const name = strip(trimmed.slice(0, space));
      const constraint = strip(trimmed.slice(space + 1)) || '*';
      return constraint === '*' ? name : name + '@' + constraint;
    }
    return trimmed;
  }

  function parseDependencies(raw) {
    const value = strip(raw);
    if (value === '') {
      return [];
    }
    const out = [];
    for (const item of value.split(',')) {
      const normalized = normalizeDependency(item);
      if (normalized !== null) {
        out.push(normalized);
      }
    }
    return out;
  }

  function parseNameList(raw) {
    const value = strip(raw);
    if (value === '') {
      return [];
    }
    const out = [];
    const seen = new Set();
    for (const item of value.split(/[\s,]+/)) {
      const name = strip(item);
      if (name !== '' && !seen.has(name)) {
        seen.add(name);
        out.push(name);
      }
    }
    return out;
  }

  function parseFileLine(raw, line, files) {
    const trimmed = raw.trim();
    const parts = trimmed.split('\t');
    if (parts.length < 3) {
      fail(
        'línea de archivo inválida' +
          (line ? ' (línea ' + line + ')' : '') +
          ': se esperan ruta, tamaño y sha256 separados por tabuladores'
      );
    }
    const path = strip(parts[0]);
    if (path === '') {
      fail('ruta de archivo vacía' + (line ? ' (línea ' + line + ')' : ''));
    }
    const size = parseLong(parts[1], 'el tamaño del archivo', line);
    const sha256 = strip(parts[2]);
    if (!SHA256.test(sha256)) {
      fail('sha256 inválido en el archivo ' + path + (line ? ' (línea ' + line + ')' : '') + ': ' + sha256);
    }
    const owner = parts.length > 3 ? strip(parts.slice(3).join('\t')) : '';
    if (files.some(file => file.path === path)) {
      fail('ruta de archivo duplicada en la misma entrada: ' + path);
    }
    files.push({ path, size, sha256, owner });
  }

  function splitBlocks(source) {
    const blocks = [];
    let current = null;
    const lines = source.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (line.trim() === '') {
        current = null;
        continue;
      }
      if (current === null) {
        current = { lines: [], number: i + 1 };
        blocks.push(current);
      }
      current.lines.push({ text: line, number: i + 1 });
    }
    return blocks;
  }

  function requireField(fields, key, blockNumber) {
    const value = fields.has(key) ? strip(fields.get(key)) : '';
    if (value === '') {
      fail('bloque ' + blockNumber + ': falta el campo obligatorio ' + key);
    }
    return value;
  }

  function parseBlock(block) {
    const fields = new Map();
    const files = [];
    let currentKey = null;
    let currentValue = '';
    let inFiles = false;

    function flush() {
      if (currentKey === null) {
        return;
      }
      if (fields.has(currentKey)) {
        fail('bloque ' + block.number + ': campo duplicado ' + currentKey);
      }
      fields.set(currentKey, strip(currentValue));
      currentKey = null;
      currentValue = '';
    }

    for (const item of block.lines) {
      if (inFiles) {
        if (item.text.indexOf(': ') > 0) {
          inFiles = false;
        } else {
          parseFileLine(item.text, item.number, files);
          continue;
        }
      }

      const separator = item.text.indexOf(': ');
      if (separator > 0) {
        flush();
        const key = strip(item.text.slice(0, separator));
        if (key === '') {
          fail('bloque ' + block.number + ': nombre de campo vacío (línea ' + item.number + ')');
        }
        if (key === 'Files') {
          if (fields.has('Files')) {
            fail('bloque ' + block.number + ': sección Files duplicada');
          }
          fields.set('Files', '');
          inFiles = true;
          continue;
        }
        currentKey = key;
        currentValue = item.text.slice(separator + 2);
        continue;
      }

      if (currentKey !== null && /^[ \t]/.test(item.text)) {
        currentValue += '\n' + strip(item.text);
        continue;
      }

      fail(
        'bloque ' +
          block.number +
          ': línea no reconocida en la base de paquetes (línea ' +
          item.number +
          '): ' +
          item.text
      );
    }
    flush();

    const name = requireField(fields, 'Name', block.number);
    const version = requireField(fields, 'Version', block.number);
    const channel = requireField(fields, 'Channel', block.number);
    const type = requireField(fields, 'Type', block.number).toUpperCase();
    if (!TYPES.includes(type)) {
      fail('bloque ' + block.number + ': tipo de paquete inválido: ' + type);
    }

    const sha256 = fields.has('SHA256') ? strip(fields.get('SHA256')) : '';
    if (sha256 !== '' && !SHA256.test(sha256)) {
      fail('bloque ' + block.number + ': sha256 inválido: ' + sha256);
    }
    const size = parseLong(fields.has('Size') ? fields.get('Size') : '0', 'el tamaño del paquete', null);

    const extra = {};
    for (const [key, value] of fields) {
      if (!KNOWN.has(key)) {
        extra[key] = value;
      }
    }

    return {
      name,
      version,
      channel,
      type,
      dependencies: parseDependencies(fields.get('Dependencies') || ''),
      exclude: parseNameList(fields.get('Exclude') || ''),
      provide: parseNameList(fields.get('Provide') || ''),
      arch: fields.has('Arch') ? strip(fields.get('Arch')) : 'none',
      platform: fields.has('Platform') ? strip(fields.get('Platform')) : 'common',
      url: fields.has('URL') ? strip(fields.get('URL')) : '',
      os: fields.has('OS') ? strip(fields.get('OS')) : 'none',
      sha256,
      size,
      sourceArtifact: fields.has('SourceArtifact') ? strip(fields.get('SourceArtifact')) : '',
      parentPackage: fields.has('ParentPackage') ? strip(fields.get('ParentPackage')) : '',
      extra,
      files,
    };
  }

  function idOf(entry) {
    return text(entry.name) + '_' + text(entry.version) + '_' + text(entry.channel);
  }

  function parse(source) {
    const normalized = normalizeSource(source);
    const entries = splitBlocks(normalized).map(parseBlock);
    validate(entries);
    return entries;
  }

  function validateString(value, what) {
    if (typeof value !== 'string') {
      fail(what + ' debe ser texto');
    }
    if (/[\r\n\u0000]/.test(value)) {
      fail(what + ' no puede contener saltos de línea ni NUL');
    }
  }

  function validateNameList(value, what) {
    if (!Array.isArray(value)) {
      fail(what + ' debe ser una lista');
    }
    for (const item of value) {
      validateString(item, what);
      if (item === '' || /[\s,]/.test(item)) {
        fail(what + ' contiene un valor vacío, con espacios o con comas: ' + item);
      }
    }
  }

  function validate(entries) {
    if (!Array.isArray(entries)) {
      fail('la base de paquetes debe ser una lista de entradas');
    }
    const ids = new Set();
    entries.forEach((entry, index) => {
      const where = 'entrada ' + (index + 1);
      if (!isMapLike(entry)) {
        fail(where + ': se esperaba un objeto');
      }
      for (const key of ['name', 'version', 'channel', 'type']) {
        validateString(entry[key], where + ': ' + key);
        if (strip(entry[key]) === '') {
          fail(where + ': falta ' + key);
        }
      }
      const type = strip(entry.type).toUpperCase();
      if (!TYPES.includes(type)) {
        fail(where + ': tipo de paquete inválido: ' + entry.type);
      }
      for (const key of ['arch', 'platform', 'url', 'os', 'sha256', 'sourceArtifact', 'parentPackage']) {
        if (entry[key] !== undefined) {
          validateString(entry[key], where + ': ' + key);
        }
      }
      if (entry.sha256 !== undefined && strip(entry.sha256) !== '' && !SHA256.test(strip(entry.sha256))) {
        fail(where + ': sha256 inválido: ' + entry.sha256);
      }
      if (!Number.isSafeInteger(entry.size) || entry.size < 0) {
        fail(where + ': el tamaño debe ser un entero no negativo');
      }
      validateNameList(entry.exclude || [], where + ': exclude');
      validateNameList(entry.provide || [], where + ': provide');
      if (!Array.isArray(entry.dependencies || [])) {
        fail(where + ': dependencies debe ser una lista');
      }
      for (const dependency of entry.dependencies || []) {
        validateString(dependency, where + ': dependencia');
        if (strip(dependency) === '' || dependency.indexOf(',') !== -1) {
          fail(where + ': dependencia inválida: ' + dependency);
        }
      }
      if (!Array.isArray(entry.files || [])) {
        fail(where + ': files debe ser una lista');
      }
      const paths = new Set();
      for (const file of entry.files || []) {
        if (!isMapLike(file)) {
          fail(where + ': cada archivo debe ser un objeto');
        }
        validateString(file.path, where + ': ruta de archivo');
        if (strip(file.path) === '') {
          fail(where + ': ruta de archivo vacía');
        }
        if (paths.has(file.path)) {
          fail(where + ': ruta de archivo duplicada: ' + file.path);
        }
        paths.add(file.path);
        if (!Number.isSafeInteger(file.size) || file.size < 0) {
          fail(where + ': tamaño inválido para ' + file.path);
        }
        if (!SHA256.test(strip(file.sha256))) {
          fail(where + ': sha256 inválido para ' + file.path);
        }
        validateString(file.owner || '', where + ': dueño de ' + file.path);
      }
      if (entry.extra !== undefined && !isMapLike(entry.extra)) {
        fail(where + ': extra debe ser un objeto');
      }
      for (const [key, value] of Object.entries(entry.extra || {})) {
        if (key === '' || key.indexOf(':') !== -1 || /[\r\n\u0000]/.test(key)) {
          fail(where + ': nombre de campo extra inválido: ' + key);
        }
        if (KNOWN.has(key)) {
          fail(where + ': el campo extra ' + key + ' es un campo conocido');
        }
        if (typeof value !== 'string') {
          fail(where + ': el campo extra ' + key + ' debe ser texto');
        }
        if (value.indexOf('\u0000') !== -1) {
          fail(where + ': el campo extra ' + key + ' contiene NUL');
        }
      }
      const id = idOf(entry);
      if (ids.has(id)) {
        fail('identificador de paquete duplicado: ' + id);
      }
      ids.add(id);
    });
  }

  function formatExtra(key, value) {
    const parts = text(value).split('\n');
    let out = key + ': ' + parts[0] + '\n';
    for (let i = 1; i < parts.length; i++) {
      out += '  ' + parts[i] + '\n';
    }
    return out;
  }

  function serializeEntry(entry) {
    let out = '';
    out += 'Name: ' + text(entry.name) + '\n';
    out += 'Version: ' + text(entry.version) + '\n';
    out += 'Channel: ' + text(entry.channel) + '\n';
    out += 'Type: ' + strip(entry.type).toUpperCase() + '\n';
    out += 'Dependencies: ' + (entry.dependencies || []).join(',') + '\n';
    if ((entry.exclude || []).length > 0) {
      out += 'Exclude: ' + entry.exclude.join(' ') + '\n';
    }
    if ((entry.provide || []).length > 0) {
      out += 'Provide: ' + entry.provide.join(' ') + '\n';
    }
    out += 'Arch: ' + text(entry.arch) + '\n';
    out += 'Platform: ' + text(entry.platform) + '\n';
    out += 'URL: ' + text(entry.url) + '\n';
    out += 'OS: ' + text(entry.os) + '\n';
    out += 'SHA256: ' + text(entry.sha256) + '\n';
    out += 'Size: ' + entry.size + '\n';
    if (text(entry.sourceArtifact) !== '') {
      out += 'SourceArtifact: ' + text(entry.sourceArtifact) + '\n';
    }
    if (text(entry.parentPackage) !== '') {
      out += 'ParentPackage: ' + text(entry.parentPackage) + '\n';
    }
    for (const [key, value] of Object.entries(entry.extra || {})) {
      out += formatExtra(key, value);
    }
    out += 'Files: \n';
    for (const file of entry.files || []) {
      out += '  ' + file.path + '\t' + file.size + '\t' + file.sha256 + '\t' + text(file.owner) + '\n';
    }
    return out;
  }

  function compare(left, right) {
    const a = text(left);
    const b = text(right);
    return a < b ? -1 : a > b ? 1 : 0;
  }

  function serialize(entries) {
    validate(entries);
    const sorted = entries.slice().sort((a, b) => {
      return (
        compare(a.name, b.name) ||
        compare(a.version, b.version) ||
        compare(a.channel, b.channel) ||
        compare(a.type, b.type)
      );
    });
    let out = '';
    sorted.forEach((entry, index) => {
      if (index > 0) {
        out += '\n';
      }
      out += serializeEntry(entry);
    });
    return out;
  }

  function canonical(entries) {
    return serialize(entries);
  }

  function create() {
    return {
      name: 'new-package',
      version: '1.0.0',
      channel: 'stable',
      type: 'PACKAGE',
      dependencies: [],
      exclude: [],
      provide: [],
      arch: 'none',
      platform: 'common',
      url: '',
      os: 'none',
      sha256: '',
      size: 0,
      sourceArtifact: '',
      parentPackage: '',
      extra: {},
      files: [],
    };
  }

  function parseFiles(source) {
    const files = [];
    const lines = text(source).split('\n');
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].trim() === '') {
        continue;
      }
      parseFileLine(lines[i], i + 1, files);
    }
    return files;
  }

  function formatFiles(files) {
    return (files || [])
      .map(file => file.path + '\t' + file.size + '\t' + file.sha256 + '\t' + text(file.owner))
      .join('\n');
  }

  global.Suite = global.Suite || {};
  global.Suite.packageDb = {
    PackageDbError,
    TYPES,
    KNOWN_FIELDS,
    parse,
    serialize,
    serializeEntry,
    canonical,
    create,
    validate,
    normalizeDependency,
    parseDependencies,
    parseNameList,
    parseFiles,
    formatFiles,
    idOf,
  };
})(window || this);

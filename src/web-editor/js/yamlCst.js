/* yamlCst.js — parser y editor YAML que conserva el formato (CST).
 *
 * El CST guarda la posición exacta de cada nodo y el texto original. `apply`
 * compara el baseline hidratado con el estado actual y aplica solo el delta,
 * conservando comentarios, orden de claves, comillas, sangría y saltos de
 * línea. Si una edición no se puede representar sin perder formato, falla con
 * `CstError` en lugar de reescribir el archivo.
 *
 * Subconjunto soportado: mapas y secuencias de bloque, escalares planos y
 * entre comillas, colecciones de flujo en una línea, comentarios y líneas en
 * blanco. Se rechazan explícitamente los block scalars, anclas, alias, tags,
 * claves complejas, documentos múltiples, comillas o flujo multilínea, tabs en
 * la sangría y saltos de línea mixtos. */
(function (global) {
  'use strict';

  const CONFIG_VERSION_KEY = 'config-version';
  const CONFIG_VERSION_LINE = 'config-version: 1.0\n';

  class CstError extends Error {
    constructor(message, line) {
      super(line ? message + ' (línea ' + line + ')' : message);
      this.name = 'CstError';
      this.line = line || null;
    }
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

  function isMap(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }

  function isCollection(value) {
    return value !== null && typeof value === 'object';
  }

  function clone(value) {
    return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
  }

  function stripMeta(value) {
    if (!isMap(value)) {
      return clone(value);
    }
    const out = {};
    for (const [key, item] of Object.entries(value)) {
      if (key === CONFIG_VERSION_KEY) {
        continue;
      }
      out[key] = clone(item);
    }
    return out;
  }

  /* ── líneas ─────────────────────────────────────────────── */
  function splitLines(text) {
    let crlf = 0;
    let lf = 0;
    let cr = 0;
    for (let i = 0; i < text.length; i++) {
      if (text[i] === '\r') {
        if (text[i + 1] === '\n') {
          crlf++;
          i++;
        } else {
          cr++;
        }
      } else if (text[i] === '\n') {
        lf++;
      }
    }
    if (cr > 0) {
      throw new CstError('salto de línea CR aislado no soportado');
    }
    if (crlf > 0 && lf > 0) {
      throw new CstError('saltos de línea mixtos no soportados');
    }
    const eol = crlf > 0 ? '\r\n' : '\n';
    const lines = [];
    let start = 0;
    while (start <= text.length) {
      const end = text.indexOf('\n', start);
      if (end === -1) {
        lines.push({ start, contentEnd: text.length, end: text.length, text: text.slice(start), eol: '' });
        break;
      }
      let contentEnd = end;
      if (contentEnd > start && text[contentEnd - 1] === '\r') {
        contentEnd--;
      }
      lines.push({
        start,
        contentEnd,
        end: end + 1,
        text: text.slice(start, contentEnd),
        eol: eol,
      });
      start = end + 1;
    }
    return { lines, eol };
  }

  function indentOf(line) {
    let i = 0;
    while (i < line.text.length && line.text[i] === ' ') {
      i++;
    }
    if (line.text[i] === '\t') {
      throw new CstError('tabulaciones en la sangría no soportadas', lineNumber(line));
    }
    return i;
  }

  function lineNumber(line) {
    return line && line.number ? line.number : null;
  }

  /* ── comentarios y comillas ─────────────────────────────── */
  function findInlineComment(text, start, end) {
    let quote = null;
    let depth = 0;
    for (let i = start; i < end; i++) {
      const c = text[i];
      if (quote === "'") {
        if (c === "'" && text[i + 1] === "'") {
          i++;
        } else if (c === "'") {
          quote = null;
        }
        continue;
      }
      if (quote === '"') {
        if (c === '\\') {
          i++;
        } else if (c === '"') {
          quote = null;
        }
        continue;
      }
      if (c === "'" || c === '"') {
        quote = c;
        continue;
      }
      if (c === '[' || c === '{') {
        depth++;
        continue;
      }
      if (c === ']' || c === '}') {
        depth--;
        continue;
      }
      if (c === '#' && (i === start || text[i - 1] === ' ' || text[i - 1] === '\t')) {
        if (depth > 0) {
          throw new CstError('comentarios dentro de una colección de flujo no soportados');
        }
        let contentEnd = i;
        while (contentEnd > start && (text[contentEnd - 1] === ' ' || text[contentEnd - 1] === '\t')) {
          contentEnd--;
        }
        return { contentEnd, commentStart: i, commentEnd: end };
      }
    }
    let contentEnd = end;
    while (contentEnd > start && (text[contentEnd - 1] === ' ' || text[contentEnd - 1] === '\t')) {
      contentEnd--;
    }
    return { contentEnd, commentStart: -1, commentEnd: -1 };
  }

  function unquoteDouble(raw, line) {
    if (raw.length < 2 || raw[0] !== '"' || raw[raw.length - 1] !== '"') {
      throw new CstError('comilla doble sin cerrar', line);
    }
    const body = raw.slice(1, -1);
    let out = '';
    for (let i = 0; i < body.length; i++) {
      const c = body[i];
      if (c !== '\\') {
        out += c;
        continue;
      }
      const next = body[++i];
      if (next === undefined) {
        throw new CstError('escape incompleto en comilla doble', line);
      }
      switch (next) {
        case '0':
          out += '\0';
          break;
        case 'a':
          out += '\x07';
          break;
        case 'b':
          out += '\b';
          break;
        case 't':
          out += '\t';
          break;
        case 'n':
          out += '\n';
          break;
        case 'v':
          out += '\v';
          break;
        case 'f':
          out += '\f';
          break;
        case 'r':
          out += '\r';
          break;
        case 'e':
          out += '\x1b';
          break;
        case ' ':
          out += ' ';
          break;
        case '"':
          out += '"';
          break;
        case '/':
          out += '/';
          break;
        case '\\':
          out += '\\';
          break;
        case 'N':
          out += '\u0085';
          break;
        case '_':
          out += '\u00a0';
          break;
        case 'L':
          out += '\u2028';
          break;
        case 'P':
          out += '\u2029';
          break;
        case 'x': {
          const hex = body.slice(i + 1, i + 3);
          if (!/^[0-9a-fA-F]{2}$/.test(hex)) {
            throw new CstError('escape \\x inválido', line);
          }
          out += String.fromCharCode(parseInt(hex, 16));
          i += 2;
          break;
        }
        case 'u': {
          const hex = body.slice(i + 1, i + 5);
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) {
            throw new CstError('escape \\u inválido', line);
          }
          out += String.fromCharCode(parseInt(hex, 16));
          i += 4;
          break;
        }
        case 'U': {
          const hex = body.slice(i + 1, i + 9);
          if (!/^[0-9a-fA-F]{8}$/.test(hex)) {
            throw new CstError('escape \\U inválido', line);
          }
          out += String.fromCodePoint(parseInt(hex, 16));
          i += 8;
          break;
        }
        default:
          throw new CstError('escape desconocido \\' + next + ' en comilla doble', line);
      }
    }
    return out;
  }

  function unquoteSingle(raw, line) {
    if (raw.length < 2 || raw[0] !== "'" || raw[raw.length - 1] !== "'") {
      throw new CstError('comilla simple sin cerrar', line);
    }
    return raw.slice(1, -1).replace(/''/g, "'");
  }

  function scalarStyle(raw) {
    if (raw.startsWith('"')) {
      return 'double';
    }
    if (raw.startsWith("'")) {
      return 'single';
    }
    return 'plain';
  }

  function parsePlain(raw, line) {
    if (raw === '' || raw === '~' || raw === 'null') {
      return null;
    }
    if (/^-?\d+$/.test(raw)) {
      return parseInt(raw, 10);
    }
    if (/^-?(\d+\.\d*|\.\d+)(e[-+]?\d+)?$/i.test(raw)) {
      return parseFloat(raw);
    }
    if (raw === 'true') {
      return true;
    }
    if (raw === 'false') {
      return false;
    }
    if (raw.includes(': ')) {
      throw new CstError('escalar plano ambiguo con ": "', line);
    }
    return raw;
  }

  function parseScalarToken(raw, line) {
    const style = scalarStyle(raw);
    if (style === 'double') {
      return unquoteDouble(raw, line);
    }
    if (style === 'single') {
      return unquoteSingle(raw, line);
    }
    return parsePlain(raw, line);
  }

  function parseKeyRaw(raw, line) {
    const style = scalarStyle(raw);
    if (style === 'double') {
      return unquoteDouble(raw, line);
    }
    if (style === 'single') {
      return unquoteSingle(raw, line);
    }
    if (raw === '') {
      throw new CstError('clave vacía no soportada', line);
    }
    if (/^[?&*!]/.test(raw)) {
      throw new CstError('claves complejas, anclas, alias o tags no soportados', line);
    }
    return raw;
  }

  function isPlainScalar(value) {
    if (typeof value !== 'string' || value === '') {
      return false;
    }
    if (/^(true|false|null|~|yes|no|on|off)$/i.test(value)) {
      return false;
    }
    if (/^[-+0-9]/.test(value) && /^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i.test(value)) {
      return false;
    }
    if (/^[-?[\]{}!,#&*|>'"%@`]/.test(value)) {
      return false;
    }
    if (/[\n\r\t]/.test(value)) {
      return false;
    }
    if (value !== value.trim()) {
      return false;
    }
    if (value.includes(': ') || /:\s*$/.test(value)) {
      return false;
    }
    if (value.includes(' #')) {
      return false;
    }
    return true;
  }

  function formatScalar(value, style) {
    if (value === null || value === undefined) {
      return 'null';
    }
    if (typeof value === 'boolean') {
      return value ? 'true' : 'false';
    }
    if (typeof value === 'number') {
      return String(value);
    }
    if (typeof value === 'string') {
      if (style === 'single') {
        return "'" + value.replace(/'/g, "''") + "'";
      }
      if (style === 'double') {
        return JSON.stringify(value);
      }
      return isPlainScalar(value) ? value : JSON.stringify(value);
    }
    return JSON.stringify(value);
  }

  function formatKey(key) {
    return isPlainScalar(key) && !key.includes(':') ? key : JSON.stringify(key);
  }

  /* ── flujo ──────────────────────────────────────────────── */
  function parseFlowAt(text, start, end, line) {
    let i = start;
    let depth = 0;
    let quote = null;
    for (; i < end; i++) {
      const c = text[i];
      if (quote === "'") {
        if (c === "'" && text[i + 1] === "'") {
          i++;
        } else if (c === "'") {
          quote = null;
        }
        continue;
      }
      if (quote === '"') {
        if (c === '\\') {
          i++;
        } else if (c === '"') {
          quote = null;
        }
        continue;
      }
      if (c === "'" || c === '"') {
        quote = c;
        continue;
      }
      if (c === '[' || c === '{') {
        depth++;
      } else if (c === ']' || c === '}') {
        depth--;
        if (depth === 0) {
          i++;
          break;
        }
      }
    }
    if (depth !== 0 || quote) {
      throw new CstError('colección de flujo multilínea o sin cerrar no soportada', line);
    }
    const parser = { text, line };
    const node = parseFlowNode(parser, start);
    if (node.end !== i) {
      throw new CstError('texto inesperado después de la colección de flujo', line);
    }
    return node;
  }

  function skipFlowSpace(parser, pos, end) {
    while (pos < end && (parser.text[pos] === ' ' || parser.text[pos] === '\t')) {
      pos++;
    }
    return pos;
  }

  function parseFlowNode(parser, start) {
    const text = parser.text;
    const line = parser.line;
    let pos = skipFlowSpace(parser, start, text.length);
    const c = text[pos];
    if (c === '[') {
      return parseFlowSeq(parser, pos);
    }
    if (c === '{') {
      return parseFlowMap(parser, pos);
    }
    if (c === '&' || c === '*' || c === '!') {
      throw new CstError('anclas, alias y tags no soportados', line);
    }
    if (c === '|' || c === '>') {
      throw new CstError('block scalars no soportados', line);
    }
    const tokenStart = pos;
    let quote = null;
    while (pos < text.length) {
      const ch = text[pos];
      if (quote === "'") {
        if (ch === "'" && text[pos + 1] === "'") {
          pos += 2;
          continue;
        }
        if (ch === "'") {
          quote = null;
        }
        pos++;
        continue;
      }
      if (quote === '"') {
        if (ch === '\\') {
          pos += 2;
          continue;
        }
        if (ch === '"') {
          quote = null;
        }
        pos++;
        continue;
      }
      if (ch === "'" || ch === '"') {
        quote = ch;
        pos++;
        continue;
      }
      if (ch === ',' || ch === ']' || ch === '}') {
        break;
      }
      pos++;
    }
    if (quote) {
      throw new CstError('comilla sin cerrar en flujo', line);
    }
    const raw = text.slice(tokenStart, pos).trim();
    if (raw === '') {
      throw new CstError('elemento vacío en flujo', line);
    }
    return {
      kind: 'scalar',
      start: tokenStart,
      end: pos,
      raw,
      style: scalarStyle(raw),
      value: parseScalarToken(raw, line),
    };
  }

  function parseFlowSeq(parser, start) {
    const text = parser.text;
    const line = parser.line;
    const node = { kind: 'flowseq', start, end: start, items: [], value: [] };
    let pos = start + 1;
    for (;;) {
      pos = skipFlowSpace(parser, pos, text.length);
      if (text[pos] === ']') {
        pos++;
        break;
      }
      if (pos >= text.length) {
        throw new CstError('secuencia de flujo sin cerrar', line);
      }
      const item = parseFlowNode(parser, pos);
      node.items.push(item);
      node.value.push(item.value);
      pos = skipFlowSpace(parser, item.end, text.length);
      if (text[pos] === ',') {
        pos++;
        continue;
      }
      if (text[pos] === ']') {
        pos++;
        break;
      }
      throw new CstError('separador inesperado en secuencia de flujo', line);
    }
    node.end = pos;
    return node;
  }

  function parseFlowMap(parser, start) {
    const text = parser.text;
    const line = parser.line;
    const node = { kind: 'flowmap', start, end: start, entries: [], value: {} };
    let pos = start + 1;
    for (;;) {
      pos = skipFlowSpace(parser, pos, text.length);
      if (text[pos] === '}') {
        pos++;
        break;
      }
      if (pos >= text.length) {
        throw new CstError('mapa de flujo sin cerrar', line);
      }
      const keyStart = pos;
      let quote = null;
      while (pos < text.length) {
        const ch = text[pos];
        if (quote === "'") {
          if (ch === "'" && text[pos + 1] === "'") {
            pos += 2;
            continue;
          }
          if (ch === "'") {
            quote = null;
          }
          pos++;
          continue;
        }
        if (quote === '"') {
          if (ch === '\\') {
            pos += 2;
            continue;
          }
          if (ch === '"') {
            quote = null;
          }
          pos++;
          continue;
        }
        if (ch === "'" || ch === '"') {
          quote = ch;
          pos++;
          continue;
        }
        if (ch === ':' && (text[pos + 1] === ' ' || text[pos + 1] === '\t')) {
          break;
        }
        if (ch === ',' || ch === '}') {
          throw new CstError('entrada de flujo sin ":"', line);
        }
        pos++;
      }
      if (text[pos] !== ':') {
        throw new CstError('entrada de flujo sin ":"', line);
      }
      const keyRaw = text.slice(keyStart, pos).trim();
      const key = parseKeyRaw(keyRaw, line);
      pos++;
      const valueNode = parseFlowNode(parser, pos);
      node.entries.push({ key, keyRaw, keyStart, keyEnd: pos - 1, value: valueNode });
      node.value[key] = valueNode.value;
      pos = skipFlowSpace(parser, valueNode.end, text.length);
      if (text[pos] === ',') {
        pos++;
        continue;
      }
      if (text[pos] === '}') {
        pos++;
        break;
      }
      throw new CstError('separador inesperado en mapa de flujo', line);
    }
    node.end = pos;
    return node;
  }

  function formatFlowValue(value) {
    if (Array.isArray(value)) {
      return '[' + value.map(formatFlowValue).join(', ') + ']';
    }
    if (isMap(value)) {
      const keys = Object.keys(value);
      if (keys.length === 0) {
        return '{}';
      }
      return '{ ' + keys.map(key => formatKey(key) + ': ' + formatFlowValue(value[key])).join(', ') + ' }';
    }
    return formatScalar(value, 'plain');
  }

  /* ── bloque ─────────────────────────────────────────────── */
  function nextSignificant(lines, i) {
    while (i < lines.length) {
      const line = lines[i];
      const trimmed = line.text.trim();
      if (trimmed === '' || trimmed.startsWith('#')) {
        i++;
        continue;
      }
      if (trimmed === '---' || trimmed === '...' || trimmed.startsWith('%')) {
        throw new CstError('documentos múltiples o directivas no soportados', line.number);
      }
      return i;
    }
    return i;
  }

  function isSeqLine(line, indent) {
    const rest = line.text.slice(indent);
    return rest === '-' || rest.startsWith('- ') || rest.startsWith('-\t');
  }

  function findKeySepInText(text, start, end) {
    let quote = null;
    let depth = 0;
    for (let i = start; i < end; i++) {
      const c = text[i];
      if (quote === "'") {
        if (c === "'" && text[i + 1] === "'") {
          i++;
        } else if (c === "'") {
          quote = null;
        }
        continue;
      }
      if (quote === '"') {
        if (c === '\\') {
          i++;
        } else if (c === '"') {
          quote = null;
        }
        continue;
      }
      if (c === "'" || c === '"') {
        quote = c;
        continue;
      }
      if (c === '[' || c === '{') {
        depth++;
        continue;
      }
      if (c === ']' || c === '}') {
        depth--;
        continue;
      }
      if (depth === 0 && c === ':' && (i + 1 >= end || text[i + 1] === ' ' || text[i + 1] === '\t')) {
        return { colon: i, keyStart: start, keyEnd: i };
      }
    }
    return null;
  }

  function nullNode(offset) {
    return { kind: 'null', start: offset, end: offset, raw: '', style: 'plain', value: null };
  }

  function parseInlineValue(doc, line, restStart, contentEnd) {
    const text = doc.text;
    const absStart = line.start + restStart;
    const absEnd = line.start + contentEnd;
    const first = text[absStart];
    if (first === '[' || first === '{') {
      const node = parseFlowAt(text, absStart, absEnd, line.number);
      if (text.slice(node.end, absEnd).trim() !== '') {
        throw new CstError('texto inesperado después de la colección de flujo', line.number);
      }
      return node;
    }
    if (first === '|' || first === '>') {
      throw new CstError('block scalars no soportados', line.number);
    }
    if (first === '&' || first === '*' || first === '!') {
      throw new CstError('anclas, alias y tags no soportados', line.number);
    }
    if (first === '-' && (restStart + 1 >= contentEnd || /[ \t]/.test(text[absStart + 1]))) {
      throw new CstError('secuencia de bloque en la misma línea no soportada', line.number);
    }
    const raw = text.slice(absStart, absEnd);
    return {
      kind: 'scalar',
      start: absStart,
      end: absEnd,
      raw,
      style: scalarStyle(raw),
      value: parseScalarToken(raw, line.number),
    };
  }

  function parseMapEntry(doc, lineIndex, indent, keyStartAbs, firstInline) {
    const lines = doc.lines;
    const line = lines[lineIndex];
    const relStart = keyStartAbs - line.start;
    const comment = findInlineComment(line.text, relStart, line.text.length);
    const contentEnd = comment.contentEnd;
    const sep = findKeySepInText(line.text, relStart, contentEnd);
    if (!sep) {
      throw new CstError('se esperaba "clave: valor"', line.number);
    }
    const keyRaw = line.text.slice(relStart, sep.colon).trim();
    const key = parseKeyRaw(keyRaw, line.number);
    let restStart = sep.colon + 1;
    while (restStart < contentEnd && (line.text[restStart] === ' ' || line.text[restStart] === '\t')) {
      restStart++;
    }
    const entry = {
      key,
      keyRaw,
      keyStart: line.start + relStart,
      colonStart: line.start + sep.colon,
      colonEnd: line.start + sep.colon + 1,
      value: null,
      lineStart: line.start,
      lineEnd: line.contentEnd,
      lineEndWithEol: line.end,
      lineComment:
        comment.commentStart >= 0
          ? { start: line.start + comment.commentStart, end: line.start + comment.commentEnd }
          : null,
      indent,
      textStart: firstInline ? line.start + relStart : line.start,
      textEnd: line.end,
      includeEol: true,
      nextIndex: lineIndex + 1,
    };
    if (restStart < contentEnd) {
      entry.value = parseInlineValue(doc, line, restStart, contentEnd);
      const next = nextSignificant(lines, lineIndex + 1);
      if (next < lines.length && indentOf(lines[next]) > indent) {
        throw new CstError('un escalar no puede tener un bloque anidado', lines[next].number);
      }
      entry.nextIndex = next;
    } else {
      const next = nextSignificant(lines, lineIndex + 1);
      if (next < lines.length && indentOf(lines[next]) > indent) {
        const value = parseBlockAt(doc, next, indentOf(lines[next]));
        entry.value = value;
        entry.nextIndex = value.nextIndex;
        entry.lineEnd = value.lineEnd;
        entry.lineEndWithEol = value.lineEndWithEol;
        entry.textEnd = firstInline ? value.lineEnd : value.lineEndWithEol;
      } else {
        entry.value = nullNode(line.start + sep.colon + 1);
        entry.nextIndex = next;
      }
    }
    entry.includeEol = !firstInline || !isInlineNode(entry.value);
    entry.textEnd = entry.includeEol ? entry.lineEndWithEol : entry.lineEnd;
    return entry;
  }

  function parseMapAt(doc, i, indent) {
    const lines = doc.lines;
    const entries = [];
    let pos = i;
    for (;;) {
      const next = nextSignificant(lines, pos);
      if (next >= lines.length) {
        pos = next;
        break;
      }
      const line = lines[next];
      const lineIndent = indentOf(line);
      if (lineIndent < indent) {
        pos = next;
        break;
      }
      if (lineIndent > indent) {
        throw new CstError('sangría inesperada en el mapa', line.number);
      }
      if (isSeqLine(line, indent)) {
        throw new CstError('mapa y secuencia mezclados en el mismo nivel', line.number);
      }
      const entry = parseMapEntry(doc, next, indent, line.start + indent, false);
      entries.push(entry);
      pos = entry.nextIndex;
    }
    if (entries.length === 0) {
      throw new CstError('mapa vacío sin sintaxis de flujo no soportado', lines[i] && lines[i].number);
    }
    const first = entries[0];
    const last = entries[entries.length - 1];
    const node = {
      kind: 'map',
      start: first.lineStart,
      end: last.lineEndWithEol,
      lineEnd: last.lineEnd,
      lineEndWithEol: last.lineEndWithEol,
      indent,
      entries,
      value: {},
      isInlineAfterDash: false,
      nextIndex: pos,
    };
    for (const entry of entries) {
      if (Object.prototype.hasOwnProperty.call(node.value, entry.key)) {
        throw new CstError('clave duplicada: ' + entry.key, lines[i] && lines[i].number);
      }
      node.value[entry.key] = entry.value.value;
    }
    return node;
  }

  function parseInlineMapAt(doc, lineIndex, itemIndent, keyIndent) {
    const lines = doc.lines;
    const line = lines[lineIndex];
    const entries = [];
    const first = parseMapEntry(doc, lineIndex, keyIndent, line.start + keyIndent, true);
    entries.push(first);
    let pos = first.nextIndex;
    for (;;) {
      const next = nextSignificant(lines, pos);
      if (next >= lines.length) {
        pos = next;
        break;
      }
      const current = lines[next];
      const currentIndent = indentOf(current);
      if (currentIndent < keyIndent) {
        pos = next;
        break;
      }
      if (currentIndent > keyIndent) {
        throw new CstError('sangría inesperada en el mapa tras el guion', current.number);
      }
      if (isSeqLine(current, keyIndent)) {
        throw new CstError('mapa y secuencia mezclados en el mismo nivel', current.number);
      }
      const entry = parseMapEntry(doc, next, keyIndent, current.start + keyIndent, false);
      entries.push(entry);
      pos = entry.nextIndex;
    }
    const last = entries[entries.length - 1];
    const node = {
      kind: 'map',
      start: first.keyStart,
      end: last.lineEnd,
      lineEnd: last.lineEnd,
      lineEndWithEol: last.lineEndWithEol,
      indent: keyIndent,
      itemIndent,
      entries,
      value: {},
      isInlineAfterDash: true,
      nextIndex: pos,
    };
    for (const entry of entries) {
      if (Object.prototype.hasOwnProperty.call(node.value, entry.key)) {
        throw new CstError('clave duplicada: ' + entry.key, line.number);
      }
      node.value[entry.key] = entry.value.value;
    }
    return node;
  }

  function parseSeqItem(doc, lineIndex, indent) {
    const lines = doc.lines;
    const line = lines[lineIndex];
    const comment = findInlineComment(line.text, indent, line.text.length);
    const contentEnd = comment.contentEnd;
    let restStart = indent + 1;
    while (restStart < contentEnd && (line.text[restStart] === ' ' || line.text[restStart] === '\t')) {
      restStart++;
    }
    const item = {
      lineStart: line.start,
      lineEnd: line.contentEnd,
      lineEndWithEol: line.end,
      lineComment:
        comment.commentStart >= 0
          ? { start: line.start + comment.commentStart, end: line.start + comment.commentEnd }
          : null,
      indent,
      dashStart: line.start + indent,
      dashEnd: line.start + restStart,
      value: null,
      nextIndex: lineIndex + 1,
    };
    if (restStart < contentEnd) {
      const first = doc.text[line.start + restStart];
      if (first === '[' || first === '{') {
        item.value = parseInlineValue(doc, line, restStart, contentEnd);
      } else if (first === '|' || first === '>') {
        throw new CstError('block scalars no soportados', line.number);
      } else if (first === '&' || first === '*' || first === '!') {
        throw new CstError('anclas, alias y tags no soportados', line.number);
      } else {
        const sep = findKeySepInText(line.text, restStart, contentEnd);
        if (sep) {
          item.value = parseInlineMapAt(doc, lineIndex, indent, restStart);
          item.nextIndex = item.value.nextIndex;
          item.lineEnd = item.value.lineEnd;
          item.lineEndWithEol = item.value.lineEndWithEol;
          return item;
        }
        const raw = line.text.slice(restStart, contentEnd);
        item.value = {
          kind: 'scalar',
          start: line.start + restStart,
          end: line.start + contentEnd,
          raw,
          style: scalarStyle(raw),
          value: parseScalarToken(raw, line.number),
        };
      }
      const next = nextSignificant(lines, lineIndex + 1);
      if (next < lines.length && indentOf(lines[next]) > indent) {
        throw new CstError('un escalar no puede tener un bloque anidado', lines[next].number);
      }
      item.nextIndex = next;
    } else {
      const next = nextSignificant(lines, lineIndex + 1);
      if (next < lines.length && indentOf(lines[next]) > indent) {
        const value = parseBlockAt(doc, next, indentOf(lines[next]));
        item.value = value;
        item.nextIndex = value.nextIndex;
        item.lineEnd = value.lineEnd;
        item.lineEndWithEol = value.lineEndWithEol;
      } else {
        item.value = nullNode(line.start + indent + 1);
        item.nextIndex = next;
      }
    }
    return item;
  }

  function parseSeqAt(doc, i, indent) {
    const lines = doc.lines;
    const items = [];
    let pos = i;
    for (;;) {
      const next = nextSignificant(lines, pos);
      if (next >= lines.length) {
        pos = next;
        break;
      }
      const line = lines[next];
      const lineIndent = indentOf(line);
      if (lineIndent < indent) {
        pos = next;
        break;
      }
      if (lineIndent > indent) {
        throw new CstError('sangría inesperada en la secuencia', line.number);
      }
      if (!isSeqLine(line, indent)) {
        throw new CstError('mapa y secuencia mezclados en el mismo nivel', line.number);
      }
      const item = parseSeqItem(doc, next, indent);
      items.push(item);
      pos = item.nextIndex;
    }
    if (items.length === 0) {
      throw new CstError('secuencia vacía sin sintaxis de flujo no soportada', lines[i] && lines[i].number);
    }
    const first = items[0];
    const last = items[items.length - 1];
    return {
      kind: 'seq',
      start: first.lineStart,
      end: last.lineEndWithEol,
      lineEnd: last.lineEnd,
      lineEndWithEol: last.lineEndWithEol,
      indent,
      items,
      value: items.map(item => item.value.value),
      nextIndex: pos,
    };
  }

  function parseBlockAt(doc, i, indent) {
    const lines = doc.lines;
    const line = lines[i];
    if (isSeqLine(line, indent)) {
      return parseSeqAt(doc, i, indent);
    }
    const comment = findInlineComment(line.text, indent, line.text.length);
    const sep = findKeySepInText(line.text, indent, comment.contentEnd);
    if (sep) {
      return parseMapAt(doc, i, indent);
    }
    const trimmed = line.text.slice(indent);
    if (trimmed.startsWith('? ') || trimmed === '?' || trimmed.startsWith(': ') || trimmed === ':') {
      throw new CstError('claves complejas no soportadas', line.number);
    }
    const first = line.text[indent];
    if (first === '[' || first === '{') {
      const node = parseInlineValue(doc, line, indent, comment.contentEnd);
      const next = nextSignificant(lines, i + 1);
      node.nextIndex = next;
      node.lineEnd = line.contentEnd;
      node.lineEndWithEol = line.end;
      return node;
    }
    const raw = line.text.slice(indent, comment.contentEnd);
    const node = {
      kind: 'scalar',
      start: line.start + indent,
      end: line.start + comment.contentEnd,
      raw,
      style: scalarStyle(raw),
      value: parseScalarToken(raw, line.number),
      lineEnd: line.contentEnd,
      lineEndWithEol: line.end,
    };
    node.nextIndex = nextSignificant(lines, i + 1);
    return node;
  }

  function parse(text) {
    const source = typeof text === 'string' ? text : '';
    const bom = source.startsWith('\ufeff') ? '\ufeff' : '';
    const body = bom ? source.slice(1) : source;
    const split = splitLines(body);
    for (let i = 0; i < split.lines.length; i++) {
      split.lines[i].number = i + 1;
    }
    const doc = { text: body, bom, eol: split.eol, lines: split.lines, root: null };
    const first = nextSignificant(split.lines, 0);
    if (first < split.lines.length) {
      doc.root = parseBlockAt(doc, first, indentOf(split.lines[first]));
      const after = nextSignificant(split.lines, doc.root.nextIndex);
      if (after < split.lines.length) {
        throw new CstError('contenido inesperado al final del documento', split.lines[after].number);
      }
    }
    return doc;
  }

  function parseValue(text) {
    const doc = parse(text);
    return doc.root ? doc.root.value : null;
  }

  /* ── reconstrucción con formato ─────────────────────────── */
  function spaces(n) {
    return ' '.repeat(n);
  }

  function detectWidth(doc) {
    const indents = new Set();
    for (const line of doc.lines) {
      const trimmed = line.text.trim();
      if (trimmed === '' || trimmed.startsWith('#')) {
        continue;
      }
      indents.add(indentOf(line));
    }
    const sorted = Array.from(indents).sort((a, b) => a - b);
    for (let i = 1; i < sorted.length; i++) {
      const diff = sorted[i] - sorted[i - 1];
      if (diff > 0 && diff <= 8) {
        return diff;
      }
    }
    return 2;
  }

  function renderBlock(value, indent, ctx) {
    const pad = spaces(indent);
    if (Array.isArray(value)) {
      if (value.length === 0) {
        return pad + '[]' + ctx.eol;
      }
      let out = '';
      for (const item of value) {
        if (Array.isArray(item)) {
          out +=
            item.length === 0
              ? pad + '- []' + ctx.eol
              : pad + '-' + ctx.eol + renderBlock(item, indent + ctx.width, ctx);
        } else if (isMap(item)) {
          out += Object.keys(item).length === 0 ? pad + '- {}' + ctx.eol : renderMapAsSeqItem(item, indent, ctx);
        } else {
          out += pad + '- ' + formatScalar(item, 'plain') + ctx.eol;
        }
      }
      return out;
    }
    if (isMap(value)) {
      const keys = Object.keys(value);
      if (keys.length === 0) {
        return pad + '{}' + ctx.eol;
      }
      let out = '';
      for (const key of keys) {
        out += renderEntry(key, value[key], indent, ctx);
      }
      return out;
    }
    return pad + formatScalar(value, 'plain') + ctx.eol;
  }

  function renderEntry(key, value, indent, ctx) {
    const pad = spaces(indent);
    const keyText = formatKey(key);
    if (isCollection(value)) {
      if (Array.isArray(value) && value.length === 0) {
        return pad + keyText + ': []' + ctx.eol;
      }
      if (isMap(value) && Object.keys(value).length === 0) {
        return pad + keyText + ': {}' + ctx.eol;
      }
      return pad + keyText + ':' + ctx.eol + renderBlock(value, indent + ctx.width, ctx);
    }
    return pad + keyText + ': ' + formatScalar(value, 'plain') + ctx.eol;
  }

  function renderMapAsSeqItem(value, indent, ctx) {
    const pad = spaces(indent);
    const keys = Object.keys(value);
    if (keys.length === 0) {
      return pad + '- {}' + ctx.eol;
    }
    const keyIndent = indent + 2;
    let out = '';
    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      const item = value[key];
      const prefix = i === 0 ? pad + '- ' : spaces(keyIndent);
      if (isCollection(item)) {
        if (Array.isArray(item) && item.length === 0) {
          out += prefix + formatKey(key) + ': []' + ctx.eol;
        } else if (isMap(item) && Object.keys(item).length === 0) {
          out += prefix + formatKey(key) + ': {}' + ctx.eol;
        } else {
          out += prefix + formatKey(key) + ':' + ctx.eol + renderBlock(item, keyIndent + ctx.width, ctx);
        }
      } else {
        out += prefix + formatKey(key) + ': ' + formatScalar(item, 'plain') + ctx.eol;
      }
    }
    return out;
  }

  function renderNewItem(value, indent, ctx) {
    const pad = spaces(indent);
    if (Array.isArray(value)) {
      if (value.length === 0) {
        return pad + '- []' + ctx.eol;
      }
      return pad + '-' + ctx.eol + renderBlock(value, indent + ctx.width, ctx);
    }
    if (isMap(value)) {
      if (Object.keys(value).length === 0) {
        return pad + '- {}' + ctx.eol;
      }
      return renderMapAsSeqItem(value, indent, ctx);
    }
    return pad + '- ' + formatScalar(value, 'plain') + ctx.eol;
  }

  function renderFlowNode(doc, node, current) {
    if (node.kind === 'flowseq') {
      if (!Array.isArray(current)) {
        return formatFlowValue(current);
      }
      const used = new Set();
      const parts = current.map(item => {
        for (let i = 0; i < node.items.length; i++) {
          if (!used.has(i) && canonical(node.items[i].value) === canonical(item)) {
            used.add(i);
            return doc.text.slice(node.items[i].start, node.items[i].end);
          }
        }
        return formatFlowValue(item);
      });
      return '[' + parts.join(', ') + ']';
    }
    if (!isMap(current)) {
      return formatFlowValue(current);
    }
    const used = new Set();
    const keys = Object.keys(current);
    if (keys.length === 0) {
      return '{}';
    }
    const parts = keys.map(key => {
      for (let i = 0; i < node.entries.length; i++) {
        const entry = node.entries[i];
        if (!used.has(i) && entry.key === key && canonical(entry.value.value) === canonical(current[key])) {
          used.add(i);
          return doc.text.slice(entry.keyStart, entry.value.end).trimEnd();
        }
      }
      return formatKey(key) + ': ' + formatFlowValue(current[key]);
    });
    return '{ ' + parts.join(', ') + ' }';
  }

  function renderInline(node, current, ctx) {
    if (node.kind === 'scalar' || node.kind === 'null') {
      return formatScalar(current, node.kind === 'scalar' ? node.style : 'plain');
    }
    if (node.kind === 'flowseq' || node.kind === 'flowmap') {
      return isCollection(current) ? renderFlowNode(ctx.doc, node, current) : formatScalar(current, 'plain');
    }
    throw new CstError('nodo no representable como valor en línea');
  }

  function renderInlineScalar(value) {
    if (isCollection(value)) {
      return formatFlowValue(value);
    }
    return formatScalar(value, 'plain');
  }

  function isInlineNode(node) {
    return node.kind === 'scalar' || node.kind === 'null' || node.kind === 'flowseq' || node.kind === 'flowmap';
  }

  function isInlineNew(node, value) {
    if (!isCollection(value)) {
      return true;
    }
    return node.kind === 'flowseq' || node.kind === 'flowmap';
  }

  function hasCommentsInRange(doc, start, end) {
    for (const line of doc.lines) {
      if (line.start >= end) {
        break;
      }
      if (line.end <= start) {
        continue;
      }
      if (line.text.trim().startsWith('#')) {
        return true;
      }
      const from = Math.max(start, line.start) - line.start;
      const to = Math.min(end, line.contentEnd) - line.start;
      if (to > from) {
        const comment = findInlineComment(line.text, from, to);
        if (comment.commentStart >= 0) {
          return true;
        }
      }
    }
    return false;
  }

  function hasBlockComments(doc, start, end, allowed) {
    for (const line of doc.lines) {
      if (line.start >= end) {
        break;
      }
      if (line.end <= start) {
        continue;
      }
      if (line.text.trim().startsWith('#')) {
        return true;
      }
      const from = Math.max(start, line.start) - line.start;
      const to = Math.min(end, line.contentEnd) - line.start;
      if (to > from) {
        const comment = findInlineComment(line.text, from, to);
        if (comment.commentStart >= 0) {
          const abs = line.start + comment.commentStart;
          if (!allowed || allowed.start !== abs) {
            return true;
          }
        }
      }
    }
    return false;
  }

  function renderEntryText(doc, entry, baseline, current, ctx) {
    const node = entry.value;
    const original = doc.text.slice(entry.keyStart, entry.textEnd);
    if (canonical(baseline) === canonical(current)) {
      return original;
    }
    const origInline = isInlineNode(node);
    const newInline = isInlineNew(node, current);
    if (origInline && newInline) {
      if (current === null && node.kind !== 'null') {
        let out = doc.text.slice(entry.keyStart, entry.colonEnd);
        if (entry.lineComment) {
          out += ' ' + doc.text.slice(entry.lineComment.start, entry.lineComment.end);
        }
        return out + (entry.includeEol ? ctx.eol : '');
      }
      const token = renderInline(node, current, ctx);
      let prefix = doc.text.slice(entry.keyStart, node.start);
      if (node.kind === 'null' && prefix.endsWith(':')) {
        prefix += ' ';
      }
      const suffix = doc.text.slice(node.end, entry.lineEnd);
      return prefix + token + suffix + (entry.includeEol ? ctx.eol : '');
    }
    if (!origInline && !newInline) {
      const content = renderCollectionContent(doc, node, baseline, current, ctx);
      const prefix = doc.text.slice(entry.keyStart, node.start);
      const suffix = doc.text.slice(node.end, entry.lineEnd);
      const eol = entry.includeEol && !content.endsWith(ctx.eol) ? ctx.eol : '';
      return prefix + content + suffix + eol;
    }
    if (origInline && !newInline) {
      const keyLine = doc.text.slice(entry.keyStart, entry.colonEnd);
      const comment = entry.lineComment ? ' ' + doc.text.slice(entry.lineComment.start, entry.lineComment.end) : '';
      const block = renderBlock(current, entry.indent + ctx.width, ctx);
      return keyLine + comment + ctx.eol + block;
    }
    if (hasBlockComments(doc, entry.colonEnd, node.end, entry.lineComment)) {
      throw new CstError('convertir una colección con comentarios en escalar perdería formato');
    }
    const keyLine = doc.text.slice(entry.keyStart, entry.colonEnd);
    const comment = entry.lineComment ? ' ' + doc.text.slice(entry.lineComment.start, entry.lineComment.end) : '';
    return keyLine + ' ' + renderInlineScalar(current) + comment + (entry.includeEol ? ctx.eol : '');
  }

  function renderCollectionContent(doc, node, baseline, current, ctx) {
    if (node.kind === 'map' && isMap(current)) {
      return renderMapContent(doc, node, baseline, current, ctx);
    }
    if (node.kind === 'seq' && Array.isArray(current)) {
      return renderSeqContent(doc, node, baseline, current, ctx);
    }
    if (node.kind === 'flowseq' || node.kind === 'flowmap') {
      return renderFlowNode(doc, node, current);
    }
    return renderBlock(current, node.indent, ctx);
  }

  function insertNewEntries(doc, node, current, insertAt, ctx) {
    const existing = new Set(node.entries.map(entry => entry.key));
    const keys = Object.keys(current).filter(key => key !== CONFIG_VERSION_KEY && !existing.has(key));
    if (keys.length === 0) {
      return { text: '', count: 0 };
    }
    let text = '';
    for (const key of keys) {
      text += renderEntry(key, current[key], node.indent, ctx);
    }
    return { text, count: keys.length, insertAt };
  }

  function renderMapContent(doc, node, baseline, current, ctx) {
    const inline = node.isInlineAfterDash;
    if (inline && node.entries.length > 0 && !Object.prototype.hasOwnProperty.call(current, node.entries[0].key)) {
      throw new CstError('no se puede quitar la primera clave de un mapa tras el guion sin perder formato');
    }
    const configIndex = node.entries.findIndex(entry => entry.key === CONFIG_VERSION_KEY);
    let insertAt = configIndex === -1 ? node.entries.length : configIndex;
    if (inline) {
      insertAt = Math.max(1, insertAt);
    }
    const added = insertNewEntries(doc, node, current, insertAt, ctx);
    let cursor = node.start;
    let out = '';
    if (insertAt === 0 && added.count > 0) {
      out += added.text;
    }
    for (let i = 0; i < node.entries.length; i++) {
      const entry = node.entries[i];
      const retained = entry.key === CONFIG_VERSION_KEY || Object.prototype.hasOwnProperty.call(current, entry.key);
      const entryCurrent = entry.key === CONFIG_VERSION_KEY ? entry.value.value : current[entry.key];
      let entryEnd = entry.textEnd;
      if (inline && i === 0 && retained && isInlineNode(entry.value) && !isInlineNew(entry.value, entryCurrent)) {
        entryEnd = entry.lineEndWithEol;
      }
      if (retained) {
        out += doc.text.slice(cursor, entry.keyStart);
        const entryBaseline =
          entry.key === CONFIG_VERSION_KEY
            ? entry.value.value
            : Object.prototype.hasOwnProperty.call(baseline, entry.key)
              ? baseline[entry.key]
              : entry.value.value;
        out += renderEntryText(doc, entry, entryBaseline, entryCurrent, ctx);
      } else {
        out += doc.text.slice(cursor, entry.lineStart);
      }
      cursor = entryEnd;
      if (i === insertAt - 1 && added.count > 0) {
        if (!out.endsWith(ctx.eol)) {
          out += ctx.eol;
        }
        out += added.text;
      }
    }
    out += doc.text.slice(cursor, node.end);
    return out;
  }

  function renderItemText(doc, item, baseline, current, ctx) {
    const node = item.value;
    if (canonical(baseline) === canonical(current)) {
      return doc.text.slice(item.lineStart, item.lineEndWithEol);
    }
    if (
      node.kind === 'map' &&
      node.isInlineAfterDash &&
      node.entries.length > 0 &&
      !Object.prototype.hasOwnProperty.call(current, node.entries[0].key)
    ) {
      if (hasCommentsInRange(doc, item.lineStart, item.lineEndWithEol)) {
        throw new CstError('no se puede quitar la primera clave sin perder comentarios');
      }
      return renderNewItem(current, item.indent, ctx);
    }
    const origInline = isInlineNode(node);
    const newInline = isInlineNew(node, current);
    if (origInline && newInline) {
      if (current === null && node.kind !== 'null') {
        let out = doc.text.slice(item.lineStart, item.dashEnd).trimEnd();
        if (item.lineComment) {
          out += ' ' + doc.text.slice(item.lineComment.start, item.lineComment.end);
        }
        return out + ctx.eol;
      }
      const token = renderInline(node, current, ctx);
      let prefix = doc.text.slice(item.lineStart, node.start);
      if (node.kind === 'null' && prefix.endsWith('-')) {
        prefix += ' ';
      }
      const suffix = doc.text.slice(node.end, item.lineEnd);
      return prefix + token + suffix + ctx.eol;
    }
    if (!origInline && !newInline) {
      const content = renderCollectionContent(doc, node, baseline, current, ctx);
      const prefix = doc.text.slice(item.lineStart, node.start);
      const suffix = doc.text.slice(node.end, item.lineEnd);
      return prefix + content + suffix + (content.endsWith(ctx.eol) ? '' : ctx.eol);
    }
    if (origInline && !newInline) {
      const dash = doc.text.slice(item.lineStart, item.dashEnd).trimEnd();
      const comment = item.lineComment ? ' ' + doc.text.slice(item.lineComment.start, item.lineComment.end) : '';
      return dash + comment + ctx.eol + renderBlock(current, item.indent + ctx.width, ctx);
    }
    if (hasBlockComments(doc, item.dashEnd, node.end, item.lineComment)) {
      throw new CstError('convertir una colección con comentarios en escalar perdería formato');
    }
    const dash = doc.text.slice(item.lineStart, item.dashEnd).trimEnd();
    const comment = item.lineComment ? ' ' + doc.text.slice(item.lineComment.start, item.lineComment.end) : '';
    return dash + ' ' + renderInlineScalar(current) + comment + ctx.eol;
  }

  function alignSeqOps(node, baseline, current) {
    if (current.length === baseline.length && current.length === node.items.length) {
      return {
        ops: current.map((item, i) => ({ type: 'keep', original: i, current: item })),
        used: new Set(node.items.map((item, i) => i)),
      };
    }
    const used = new Set();
    const ops = [];
    let last = -1;
    for (const item of current) {
      let found = -1;
      for (let j = last + 1; j < node.items.length; j++) {
        if (!used.has(j) && canonical(node.items[j].value.value) === canonical(item)) {
          found = j;
          break;
        }
      }
      if (found >= 0) {
        used.add(found);
        ops.push({ type: 'keep', original: found, current: item });
        last = found;
      } else {
        ops.push({ type: 'new', current: item });
      }
    }
    return { ops, used };
  }

  function renderSeqContent(doc, node, baseline, current, ctx) {
    const aligned = alignSeqOps(node, baseline, current);
    let cursor = node.start;
    let origIndex = 0;
    let out = '';
    for (const op of aligned.ops) {
      if (op.type === 'keep') {
        while (origIndex < op.original) {
          const removed = node.items[origIndex];
          out += doc.text.slice(cursor, removed.lineStart);
          cursor = removed.lineEndWithEol;
          origIndex++;
        }
        const item = node.items[op.original];
        out += doc.text.slice(cursor, item.lineStart);
        const itemBaseline = op.original < baseline.length ? baseline[op.original] : item.value.value;
        out += renderItemText(doc, item, itemBaseline, op.current, ctx);
        cursor = item.lineEndWithEol;
        origIndex = op.original + 1;
      } else {
        if (out !== '' && !out.endsWith(ctx.eol)) {
          out += ctx.eol;
        }
        out += renderNewItem(op.current, node.indent, ctx);
      }
    }
    while (origIndex < node.items.length) {
      const removed = node.items[origIndex];
      out += doc.text.slice(cursor, removed.lineStart);
      cursor = removed.lineEndWithEol;
      origIndex++;
    }
    out += doc.text.slice(cursor, node.end);
    return out;
  }

  function apply(text, baseline, current, options) {
    const opts = options || {};
    const doc = parse(text);
    const base = stripMeta(baseline);
    const cur = stripMeta(current);
    const ctx = { doc, text: doc.text, eol: doc.eol, width: detectWidth(doc) };
    let out;
    if (!doc.root) {
      if (canonical(base) === canonical(cur) && !opts.ensureConfigVersion) {
        return doc.bom + doc.text;
      }
      return doc.bom + create(current, opts);
    }
    if (doc.root.kind === 'map' && isMap(cur)) {
      let content = renderMapContent(doc, doc.root, base, cur, ctx);
      if (opts.ensureConfigVersion && !doc.root.entries.some(entry => entry.key === CONFIG_VERSION_KEY)) {
        if (!content.endsWith(ctx.eol)) {
          content += ctx.eol;
        }
        content += 'config-version: 1.0' + ctx.eol;
      }
      out = doc.text.slice(0, doc.root.start) + content + doc.text.slice(doc.root.end);
    } else if (doc.root.kind === 'seq' && Array.isArray(cur)) {
      const content = renderSeqContent(doc, doc.root, base, cur, ctx);
      out = doc.text.slice(0, doc.root.start) + content + doc.text.slice(doc.root.end);
    } else if ((doc.root.kind === 'flowseq' && Array.isArray(cur)) || (doc.root.kind === 'flowmap' && isMap(cur))) {
      out = doc.text.slice(0, doc.root.start) + renderFlowNode(doc, doc.root, cur) + doc.text.slice(doc.root.end);
    } else if (isInlineNode(doc.root) && !isCollection(cur)) {
      out = doc.text.slice(0, doc.root.start) + renderInline(doc.root, cur, ctx) + doc.text.slice(doc.root.end);
    } else {
      out = create(current, opts);
    }
    return doc.bom + out;
  }

  function create(value, options) {
    const opts = options || {};
    const clean = stripMeta(value);
    const ensure = opts.ensureConfigVersion !== false;
    const ctx = { eol: '\n', width: 2 };
    if (isMap(clean)) {
      if (Object.keys(clean).length === 0) {
        return ensure ? CONFIG_VERSION_LINE : '';
      }
      let text = renderBlock(clean, 0, ctx);
      if (ensure) {
        text += CONFIG_VERSION_LINE;
      }
      return text;
    }
    return renderBlock(clean, 0, ctx);
  }

  global.Suite = global.Suite || {};
  global.Suite.yamlCst = {
    CstError,
    canonical,
    parse,
    parseValue,
    apply,
    create,
    formatScalar,
    formatFlowValue,
  };
})(window || this);

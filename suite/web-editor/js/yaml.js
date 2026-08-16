/* yaml.js — writer + parser YAML (subconjunto cerrado) para round-trip exacto.
 * Cubre: maps, secuencias, scalares, quotes, flujo [a,b]/{k:v}, comentarios.
 * Es la "fonte única" de serialización del editor. Sin dependencias. */
(function (global) {
  'use strict';

  /* ── WRITER (emit) ─────────────────────────────────────── */
  function isPlainScalar(s) {
    if (typeof s !== 'string') {
      return false;
    }
    if (s === '') {
      return false;
    }
    if (/^(true|false|null|~|yes|no|on|off)$/i.test(s)) {
      return false;
    }
    if (/^[-+0-9]/.test(s) && /^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i.test(s)) {
      return false;
    }
    if (/^[-?[\]{}!,#&*|>'"%@`]/.test(s)) {
      return false;
    }
    if (/[\n\r\t]/.test(s)) {
      return false;
    }
    if (s.includes(': ')) {
      return false;
    }
    if (/:\s*$/.test(s)) {
      return false;
    }
    if (s.includes(' #')) {
      return false;
    }
    return true;
  }
  function scalar(v) {
    if (v === null || v === undefined) {
      return 'null';
    }
    if (typeof v === 'boolean') {
      return v ? 'true' : 'false';
    }
    if (typeof v === 'number') {
      return String(v);
    }
    if (typeof v === 'string') {
      return isPlainScalar(v) ? v : JSON.stringify(v);
    }
    return String(v);
  }

  function emit(value, indent) {
    indent = indent || 0;
    const pad = '  '.repeat(indent);
    const obj = v => v !== null && typeof v === 'object';

    if (Array.isArray(value)) {
      if (value.length === 0) {
        return pad + '[]\n';
      }
      let out = '';
      for (let i = 0; i < value.length; i++) {
        const item = value[i];
        if (item !== null && typeof item === 'object') {
          const keys = Object.keys(item);
          if (keys.length === 0) {
            out += pad + '- {}\n';
            continue;
          }
          const k0 = keys[0],
            v0 = item[k0];
          if (v0 !== null && typeof v0 === 'object') {
            out += pad + '- ' + k0 + ':\n' + emit(v0, indent + 3);
          } else {
            out += pad + '- ' + k0 + ': ' + scalar(v0) + '\n';
          }
          for (let k = 1; k < keys.length; k++) {
            const key = keys[k];
            const v = item[key];
            if (v !== null && typeof v === 'object') {
              out += '  '.repeat(indent + 1) + key + ':\n' + emit(v, indent + 2);
            } else {
              out += '  '.repeat(indent + 1) + key + ': ' + scalar(v) + '\n';
            }
          }
        } else {
          out += pad + '- ' + scalar(item) + '\n';
        }
      }
      return out;
    }
    if (obj(value)) {
      const keys = Object.keys(value);
      if (keys.length === 0) {
        return pad + '{}\n';
      }
      let out = '';
      for (const k of keys) {
        const v = value[k];
        if (v !== null && typeof v === 'object') {
          out += pad + k + ':\n' + emit(v, indent + 1);
        } else {
          out += pad + k + ': ' + scalar(v) + '\n';
        }
      }
      return out;
    }
    return pad + scalar(value) + '\n';
  }

  /* ── READER ─────────────────────────────────────────────── */
  function tokenize(text) {
    const tokens = [];
    const lines = text.replace(/\r\n?/g, '\n').split('\n');
    for (const raw of lines) {
      const line = stripComment(raw);
      if (line.trim() === '') {
        continue;
      }
      const indent = line.match(/^\s*/)[0].length;
      tokens.push({ indent, text: line.slice(indent) });
    }
    return tokens;
  }
  function stripComment(line) {
    let quote = null,
      out = '';
    for (let i = 0; i < line.length; i++) {
      const c = line[i];
      if (quote) {
        out += c;
        if (c === quote && line[i - 1] !== '\\' && c !== '\\') {
          quote = null;
        }
        continue;
      }
      if (c === '"' || c === '"') {
        quote = c;
        out += c;
        continue;
      }
      if (c === '#') {
        break;
      }
      out += c;
    }
    return out;
  }
  function unquote(s) {
    if (s.length >= 2 && s[0] === '"' && s[s.length - 1] === '"') {
      try {
        return JSON.parse(s);
      } catch (e) {
        return s;
      }
    }
    if (s.length >= 2 && s[0] === '"' && s[s.length - 1] === '"') {
      return s.slice(1, -1).replace(/''/g, '"');
    }
    return s;
  }
  function splitKey(text) {
    let quote = null;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (quote) {
        if (c === quote && text[i - 1] !== '\\') {
          quote = null;
        }
        continue;
      }
      if (c === '"' || c === '"') {
        quote = c;
        continue;
      }
      if (c === ':' && (i + 1 === text.length || text[i + 1] === ' ' || text[i + 1] === '\t')) {
        return { key: unquote(text.slice(0, i).trim()), rest: text.slice(i + 1).trim() };
      }
    }
    return null;
  }
  function parseScalar(text) {
    text = text.trim();
    if (text === '' || text === '~' || text === 'null') {
      return null;
    }
    if (/^-?\d+$/.test(text)) {
      return parseInt(text, 10);
    }
    if (/^-?(\d+\.\d*|\.\d+)(e[-+]?\d+)?$/i.test(text)) {
      return parseFloat(text);
    }
    if (text === 'true') {
      return true;
    }
    if (text === 'false') {
      return false;
    }
    return unquote(text);
  }
  function splitFlow(text) {
    const parts = [];
    let cur = '',
      quote = null,
      depth = 0;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (quote) {
        cur += c;
        if (c === quote && text[i - 1] !== '\\') {
          quote = null;
        }
        continue;
      }
      if (c === '"' || c === '"') {
        quote = c;
        cur += c;
        continue;
      }
      if (c === '[' || c === '{') {
        depth++;
      }
      if (c === ']' || c === '}') {
        depth--;
      }
      if (c === ',' && depth === 0) {
        parts.push(cur.trim());
        cur = '';
        continue;
      }
      cur += c;
    }
    if (cur.trim()) {
      parts.push(cur.trim());
    }
    return parts;
  }
  function parseFlow(text) {
    text = text.trim();
    if (text.startsWith('[') && text.endsWith(']')) {
      const inner = text.slice(1, -1).trim();
      if (inner === '') {
        return [];
      }
      return splitFlow(inner).map(parseFlow);
    }
    if (text.startsWith('{') && text.endsWith('}')) {
      const inner = text.slice(1, -1).trim();
      if (inner === '') {
        return {};
      }
      const o = {};
      for (const part of splitFlow(inner)) {
        const kv = splitKey(part);
        if (kv) {
          o[kv.key] = parseFlow(kv.rest);
        }
      }
      return o;
    }
    return parseScalar(text);
  }

  function readBlock(tokens, i, indent) {
    if (i >= tokens.length) {
      return [null, i];
    }
    const t = tokens[i];
    if (t.indent < indent) {
      return [null, i];
    }
    if (t.text === '-' || t.text.startsWith('- ')) {
      return readSeq(tokens, i, indent);
    }
    if (t.text === '[]' || t.text === '{}' || (/\S/.test(t.text) && (t.text[0] === '[' || t.text[0] === '{'))) {
      return [parseFlow(t.text), i + 1];
    }
    return readMap(tokens, i, indent);
  }
  function readSeq(tokens, i, indent) {
    const arr = [];
    while (
      i < tokens.length &&
      tokens[i].indent === indent &&
      (tokens[i].text === '-' || tokens[i].text.startsWith('- '))
    ) {
      const rest = tokens[i].text === '-' ? '' : tokens[i].text.slice(2).trim();
      if (rest === '') {
        const ni = i + 1 < tokens.length ? tokens[i + 1].indent : indent + 1;
        const [val, nx] = readBlock(tokens, i + 1, ni);
        arr.push(val);
        i = nx;
      } else {
        const kv = splitKey(rest);
        const deeper = i + 1 < tokens.length && tokens[i + 1].indent > indent;
        if (kv && (kv.rest !== '' || deeper)) {
          const item = {};
          if (kv.rest === '') {
            const ni = i + 1 < tokens.length ? tokens[i + 1].indent : indent + 2;
            const [val, nx] = readBlock(tokens, i + 1, ni);
            item[kv.key] = val;
            i = nx;
          } else {
            item[kv.key] = parseFlow(kv.rest);
            i++;
          }
          while (i < tokens.length && tokens[i].indent > indent) {
            const [sub, nx] = readMap(tokens, i, tokens[i].indent);
            if (sub && typeof sub === 'object' && !Array.isArray(sub)) {
              Object.assign(item, sub);
            }
            i = nx;
          }
          arr.push(item);
        } else {
          arr.push(parseFlow(rest));
          i++;
        }
      }
    }
    return [arr, i];
  }
  function readMap(tokens, i, indent) {
    const map = {};
    while (i < tokens.length && tokens[i].indent === indent) {
      const kv = splitKey(tokens[i].text);
      if (!kv) {
        i++;
        continue;
      }
      if (kv.rest === '') {
        if (i + 1 < tokens.length && tokens[i + 1].indent > indent) {
          const [val, ni] = readBlock(tokens, i + 1, tokens[i + 1].indent);
          map[kv.key] = val;
          i = ni;
        } else {
          map[kv.key] = null;
          i++;
        }
      } else {
        map[kv.key] = parseFlow(kv.rest);
        i++;
      }
    }
    return [map, i];
  }

  function parse(text) {
    const tokens = tokenize(text);
    if (tokens.length === 0) {
      return null;
    }
    const [value] = readBlock(tokens, 0, tokens[0].indent);
    return value;
  }

  global.Suite = global.Suite || {};
  global.Suite.yaml = {
    stringify: function (value) {
      return emit(value, 0);
    },
    parse: parse,
  };
})(window || this);

/* zip.js — escritor y lector ZIP sin dependencias.
 *
 * El escritor comprime cada entrada con DEFLATE (nivel máximo) y usa STORE solo
 * cuando comprimir no reduce el tamaño; la fecha es fija para que la exportación
 * sea determinista. El lector implementa directorio central, STORE (0), DEFLATE
 * (8), CRC-32, descriptor de datos y límites anti-bomba. No soporta cifrado,
 * multi-disco ni ZIP64: falla con un error explícito en lugar de aceptar un
 * archivo dudoso. */
(function (global) {
  'use strict';

  const CRC_TABLE = (() => {
    const table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) {
        c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      }
      table[n] = c;
    }
    return table;
  })();

  function crc32(bytes) {
    let c = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) {
      c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
    }
    return (c ^ 0xffffffff) >>> 0;
  }

  function dosDateTime() {
    // fecha fija (2000-01-01 00:00) para determinismo: time=0x0000 date=0x0021
    return { time: 0x0000, date: 0x0021 };
  }
  function u16be(view, off, v) {
    view.setUint16(off, v, true);
  }
  function u32be(view, off, v) {
    view.setUint32(off, v, true);
  }
  function utf8(s) {
    return new TextEncoder().encode(s);
  }

  /* ── Compresor DEFLATE (RFC 1951) ─────────────────────────────
   * Escritor síncrono y sin dependencias. Usa LZ77 con ventana de 32 KiB,
   * búsqueda perezosa y una cadena de hasta 4096 candidatos (nivel máximo),
   * y elige el menor de: bloque dinámico, bloque fijo o bloque almacenado.
   * Los pesos se recortan si el árbol supera 15 bits, de modo que el código
   * siempre es completo y válido.
   */

  const WSIZE = 32768;
  const WMASK = WSIZE - 1;
  const MIN_MATCH = 3;
  const MAX_MATCH = 258;
  const HASH_BITS = 15;
  const HASH_SIZE = 1 << HASH_BITS;

  function reverseBits(value, bits) {
    let out = 0;
    for (let i = 0; i < bits; i++) {
      out = (out << 1) | (value & 1);
      value >>>= 1;
    }
    return out;
  }

  function BitWriter(initial) {
    this.bytes = new Uint8Array(Math.max(64, initial || 64));
    this.length = 0;
    this.buffer = 0;
    this.count = 0;
  }

  BitWriter.prototype.push = function (value) {
    if (this.length === this.bytes.length) {
      const next = new Uint8Array(this.bytes.length * 2);
      next.set(this.bytes);
      this.bytes = next;
    }
    this.bytes[this.length++] = value;
  };

  BitWriter.prototype.write = function (value, bits) {
    this.buffer |= value << this.count;
    this.count += bits;
    while (this.count >= 8) {
      this.push(this.buffer & 0xff);
      this.buffer >>>= 8;
      this.count -= 8;
    }
  };

  /* Los códigos Huffman se emiten MSB-first; el resto de campos, LSB-first. */
  BitWriter.prototype.writeCode = function (code, bits) {
    this.write(reverseBits(code, bits), bits);
  };

  BitWriter.prototype.align = function () {
    if (this.count > 0) {
      this.push(this.buffer & 0xff);
      this.buffer = 0;
      this.count = 0;
    }
  };

  BitWriter.prototype.finish = function () {
    this.align();
    return this.bytes.subarray(0, this.length);
  };

  /* Construye longitudes de código canónicas a partir de las frecuencias.
   * Si el árbol supera `maxBits`, recorta los pesos y reintenta. */
  function huffmanLengths(freqs, maxBits) {
    const count = freqs.length;
    const used = [];
    for (let i = 0; i < count; i++) {
      if (freqs[i] > 0) {
        used.push(i);
      }
    }
    let weights = freqs;
    if (used.length === 0) {
      used.push(0, 1);
      weights = freqs.slice();
      weights[0] = 1;
      weights[1] = 1;
    } else if (used.length === 1) {
      const dummy = used[0] === 0 ? 1 : 0;
      used.push(dummy);
      weights = freqs.slice();
      weights[dummy] = 1;
    }
    for (;;) {
      const lengths = buildTree(used, weights, count);
      let max = 0;
      for (let i = 0; i < count; i++) {
        if (lengths[i] > max) {
          max = lengths[i];
        }
      }
      if (max <= maxBits) {
        return lengths;
      }
      const scaled = new Uint32Array(count);
      for (const symbol of used) {
        scaled[symbol] = Math.max(1, weights[symbol] >>> 1);
      }
      weights = scaled;
    }
  }

  function buildTree(symbols, weights, count) {
    const nodeWeight = [];
    const parent = [];
    const leaves = [];
    for (const symbol of symbols) {
      leaves.push(nodeWeight.length);
      nodeWeight.push(weights[symbol]);
      parent.push(-1);
    }
    const active = leaves.slice();
    const pick = () => {
      let best = 0;
      for (let i = 1; i < active.length; i++) {
        const a = active[i];
        const b = active[best];
        if (nodeWeight[a] < nodeWeight[b] || (nodeWeight[a] === nodeWeight[b] && a < b)) {
          best = i;
        }
      }
      return active.splice(best, 1)[0];
    };
    while (active.length > 1) {
      const a = pick();
      const b = pick();
      const node = nodeWeight.length;
      nodeWeight.push(nodeWeight[a] + nodeWeight[b]);
      parent.push(-1);
      parent[a] = node;
      parent[b] = node;
      active.push(node);
    }
    const lengths = new Uint8Array(count);
    for (let i = 0; i < symbols.length; i++) {
      let depth = 0;
      let node = leaves[i];
      while (parent[node] !== -1) {
        depth++;
        node = parent[node];
      }
      lengths[symbols[i]] = depth;
    }
    return lengths;
  }

  function canonicalCodes(lengths, maxBits) {
    const counts = new Uint16Array(maxBits + 1);
    for (let i = 0; i < lengths.length; i++) {
      counts[lengths[i]]++;
    }
    counts[0] = 0;
    const next = new Uint16Array(maxBits + 1);
    let code = 0;
    for (let bits = 1; bits <= maxBits; bits++) {
      code = (code + counts[bits - 1]) << 1;
      next[bits] = code;
    }
    const codes = new Uint16Array(lengths.length);
    for (let i = 0; i < lengths.length; i++) {
      const len = lengths[i];
      if (len) {
        codes[i] = next[len]++;
      }
    }
    return codes;
  }

  function lengthSymbol(length) {
    for (let i = LENGTH_BASE.length - 1; i >= 0; i--) {
      if (length >= LENGTH_BASE[i]) {
        return i;
      }
    }
    return 0;
  }

  function distanceSymbol(distance) {
    for (let i = DISTANCE_BASE.length - 1; i >= 0; i--) {
      if (distance >= DISTANCE_BASE[i]) {
        return i;
      }
    }
    return 0;
  }

  /* LZ77: hash de 3 bytes, cadena de candidatos y búsqueda perezosa.
   * Se crea por pasada para que dos pasadas produzcan los mismos tokens. */
  function createMatcher(data) {
    const n = data.length;
    const head = new Int32Array(HASH_SIZE).fill(-1);
    const prev = new Int32Array(WSIZE).fill(-1);
    const maxChain = n >= 32 * 1024 * 1024 ? 128 : n >= 8 * 1024 * 1024 ? 512 : n >= 1024 * 1024 ? 1024 : 4096;
    const niceLength = 128;
    const goodLength = 32;
    const hashAt = pos => ((data[pos] << 10) ^ (data[pos + 1] << 5) ^ data[pos + 2]) & (HASH_SIZE - 1);

    function insert(pos) {
      if (pos + MIN_MATCH > n) {
        return;
      }
      const h = hashAt(pos);
      prev[pos & WMASK] = head[h];
      head[h] = pos;
    }

    function longest(pos, previousLength) {
      if (pos + MIN_MATCH > n) {
        return null;
      }
      let candidate = head[hashAt(pos)];
      const limit = pos - WSIZE;
      const maxLength = Math.min(MAX_MATCH, n - pos);
      let bestLength = MIN_MATCH - 1;
      let bestDistance = 0;
      let chain = previousLength >= goodLength ? maxChain >> 2 : maxChain;
      while (candidate >= 0 && candidate > limit && candidate < pos && chain-- > 0) {
        if (
          data[candidate] === data[pos] &&
          data[candidate + 1] === data[pos + 1] &&
          data[candidate + 2] === data[pos + 2]
        ) {
          let length = MIN_MATCH;
          while (length < maxLength && data[candidate + length] === data[pos + length]) {
            length++;
          }
          if (length > bestLength) {
            bestLength = length;
            bestDistance = pos - candidate;
            if (length >= niceLength || length === maxLength) {
              break;
            }
          }
        }
        candidate = prev[candidate & WMASK];
        if (candidate >= pos) {
          candidate = -1;
        }
      }
      if (bestLength < MIN_MATCH) {
        return null;
      }
      return { length: bestLength, distance: bestDistance };
    }

    return { insert, longest };
  }

  function walkTokens(data, onLiteral, onMatch) {
    const n = data.length;
    const matcher = createMatcher(data);
    let pos = 0;
    let previousLength = 1;
    while (pos < n) {
      const match = matcher.longest(pos, previousLength);
      matcher.insert(pos);
      if (!match) {
        onLiteral(data[pos]);
        previousLength = 1;
        pos++;
        continue;
      }
      if (match.length < 128 && pos + 1 < n) {
        const next = matcher.longest(pos + 1, match.length);
        if (next && next.length > match.length) {
          onLiteral(data[pos]);
          previousLength = match.length;
          pos++;
          continue;
        }
      }
      onMatch(match.length, match.distance);
      for (let i = pos + 1; i < pos + match.length; i++) {
        matcher.insert(i);
      }
      previousLength = match.length;
      pos += match.length;
    }
  }

  const FIXED_LIT_LENGTHS = (() => {
    const lengths = new Uint8Array(288);
    for (let i = 0; i <= 143; i++) {
      lengths[i] = 8;
    }
    for (let i = 144; i <= 255; i++) {
      lengths[i] = 9;
    }
    for (let i = 256; i <= 279; i++) {
      lengths[i] = 7;
    }
    for (let i = 280; i <= 287; i++) {
      lengths[i] = 8;
    }
    return lengths;
  })();
  const FIXED_LIT_CODES = canonicalCodes(FIXED_LIT_LENGTHS, 15);
  const FIXED_DIST_LENGTHS = new Uint8Array(32).fill(5);
  const FIXED_DIST_CODES = canonicalCodes(FIXED_DIST_LENGTHS, 15);

  function fixedBits(litFreq, distFreq) {
    let bits = 3;
    for (let i = 0; i < 286; i++) {
      if (litFreq[i]) {
        bits += litFreq[i] * FIXED_LIT_LENGTHS[i];
      }
    }
    for (let i = 0; i < LENGTH_BASE.length; i++) {
      if (litFreq[257 + i]) {
        bits += litFreq[257 + i] * LENGTH_EXTRA[i];
      }
    }
    for (let i = 0; i < 30; i++) {
      if (distFreq[i]) {
        bits += distFreq[i] * (5 + DISTANCE_EXTRA[i]);
      }
    }
    return bits;
  }

  function rleCodeLengths(lengths) {
    const tokens = [];
    let i = 0;
    while (i < lengths.length) {
      const value = lengths[i];
      if (value === 0) {
        let run = 1;
        while (i + run < lengths.length && lengths[i + run] === 0) {
          run++;
        }
        let remaining = run;
        while (remaining >= 11) {
          const take = Math.min(remaining, 138);
          tokens.push({ symbol: 18, extra: take - 11, bits: 7 });
          remaining -= take;
        }
        if (remaining >= 3) {
          tokens.push({ symbol: 17, extra: remaining - 3, bits: 3 });
          remaining = 0;
        }
        while (remaining > 0) {
          tokens.push({ symbol: 0, extra: 0, bits: 0 });
          remaining--;
        }
        i += run;
      } else {
        tokens.push({ symbol: value, extra: 0, bits: 0 });
        i++;
        let run = 0;
        while (i + run < lengths.length && lengths[i + run] === value) {
          run++;
        }
        let remaining = run;
        while (remaining >= 3) {
          const take = Math.min(remaining, 6);
          tokens.push({ symbol: 16, extra: take - 3, bits: 2 });
          remaining -= take;
        }
        while (remaining > 0) {
          tokens.push({ symbol: value, extra: 0, bits: 0 });
          remaining--;
        }
        i += run;
      }
    }
    return tokens;
  }

  function buildDynamic(litFreq, distFreq) {
    const litLengths = huffmanLengths(litFreq, 15);
    const distLengths = huffmanLengths(distFreq, 15);
    let literalCount = 286;
    while (literalCount > 257 && litLengths[literalCount - 1] === 0) {
      literalCount--;
    }
    let distanceCount = 30;
    while (distanceCount > 1 && distLengths[distanceCount - 1] === 0) {
      distanceCount--;
    }
    const all = new Uint8Array(literalCount + distanceCount);
    all.set(litLengths.subarray(0, literalCount), 0);
    all.set(distLengths.subarray(0, distanceCount), literalCount);
    const rle = rleCodeLengths(all);
    const clFreq = new Uint32Array(19);
    for (const token of rle) {
      clFreq[token.symbol]++;
    }
    const clLengths = huffmanLengths(clFreq, 7);
    const clCodes = canonicalCodes(clLengths, 7);
    let hclen = 19;
    while (hclen > 4 && clLengths[CODE_LENGTH_ORDER[hclen - 1]] === 0) {
      hclen--;
    }
    let bits = 3 + 5 + 5 + 4 + hclen * 3;
    for (const token of rle) {
      bits += clLengths[token.symbol] + token.bits;
    }
    for (let i = 0; i < 286; i++) {
      if (litFreq[i]) {
        bits += litFreq[i] * litLengths[i];
      }
    }
    for (let i = 0; i < LENGTH_BASE.length; i++) {
      if (litFreq[257 + i]) {
        bits += litFreq[257 + i] * LENGTH_EXTRA[i];
      }
    }
    for (let i = 0; i < 30; i++) {
      if (distFreq[i]) {
        bits += distFreq[i] * (distLengths[i] + DISTANCE_EXTRA[i]);
      }
    }
    return {
      litLengths,
      litCodes: canonicalCodes(litLengths, 15),
      distLengths,
      distCodes: canonicalCodes(distLengths, 15),
      literalCount,
      distanceCount,
      rle,
      clLengths,
      clCodes,
      hclen,
      bits,
    };
  }

  function emitFixed(data, writer) {
    writer.write(1, 1);
    writer.write(1, 2);
    walkTokens(
      data,
      byte => writer.writeCode(FIXED_LIT_CODES[byte], FIXED_LIT_LENGTHS[byte]),
      (length, distance) => {
        const ls = 257 + lengthSymbol(length);
        writer.writeCode(FIXED_LIT_CODES[ls], FIXED_LIT_LENGTHS[ls]);
        writer.write(length - LENGTH_BASE[ls - 257], LENGTH_EXTRA[ls - 257]);
        const ds = distanceSymbol(distance);
        writer.writeCode(FIXED_DIST_CODES[ds], 5);
        writer.write(distance - DISTANCE_BASE[ds], DISTANCE_EXTRA[ds]);
      }
    );
    writer.writeCode(FIXED_LIT_CODES[256], FIXED_LIT_LENGTHS[256]);
  }

  function emitDynamic(data, table, writer) {
    writer.write(1, 1);
    writer.write(2, 2);
    writer.write(table.literalCount - 257, 5);
    writer.write(table.distanceCount - 1, 5);
    writer.write(table.hclen - 4, 4);
    for (let i = 0; i < table.hclen; i++) {
      writer.write(table.clLengths[CODE_LENGTH_ORDER[i]], 3);
    }
    for (const token of table.rle) {
      writer.writeCode(table.clCodes[token.symbol], table.clLengths[token.symbol]);
      if (token.bits) {
        writer.write(token.extra, token.bits);
      }
    }
    walkTokens(
      data,
      byte => writer.writeCode(table.litCodes[byte], table.litLengths[byte]),
      (length, distance) => {
        const ls = 257 + lengthSymbol(length);
        writer.writeCode(table.litCodes[ls], table.litLengths[ls]);
        writer.write(length - LENGTH_BASE[ls - 257], LENGTH_EXTRA[ls - 257]);
        const ds = distanceSymbol(distance);
        writer.writeCode(table.distCodes[ds], table.distLengths[ds]);
        writer.write(distance - DISTANCE_BASE[ds], DISTANCE_EXTRA[ds]);
      }
    );
    writer.writeCode(table.litCodes[256], table.litLengths[256]);
  }

  function emitStored(data, writer) {
    writer.write(1, 1);
    writer.write(0, 2);
    writer.align();
    const length = data.length;
    writer.push(length & 0xff);
    writer.push((length >>> 8) & 0xff);
    writer.push(~length & 0xff);
    writer.push((~length >>> 8) & 0xff);
    for (let i = 0; i < length; i++) {
      writer.push(data[i]);
    }
  }

  /** Comprime `data` como un único stream DEFLATE crudo (sin cabecera ZIP). */
  function deflateRaw(data) {
    if (data.length === 0) {
      const empty = new BitWriter(8);
      empty.write(1, 1);
      empty.write(1, 2);
      empty.writeCode(FIXED_LIT_CODES[256], FIXED_LIT_LENGTHS[256]);
      return empty.finish();
    }
    const litFreq = new Uint32Array(286);
    const distFreq = new Uint32Array(30);
    walkTokens(
      data,
      byte => {
        litFreq[byte]++;
      },
      (length, distance) => {
        litFreq[257 + lengthSymbol(length)]++;
        distFreq[distanceSymbol(distance)]++;
      }
    );
    litFreq[256]++;
    if (litFreq[0] === 0) {
      litFreq[0] = 1; // garantiza un árbol literal completo
    }
    const fixed = fixedBits(litFreq, distFreq);
    const dynamic = buildDynamic(litFreq, distFreq);
    let mode = dynamic.bits < fixed ? 'dynamic' : 'fixed';
    let best = Math.min(dynamic.bits, fixed);
    if (data.length <= 0xffff) {
      const stored = 8 * (5 + data.length);
      if (stored < best) {
        mode = 'stored';
        best = stored;
      }
    }
    const writer = new BitWriter(Math.max(64, data.length >>> 1));
    if (mode === 'dynamic') {
      emitDynamic(data, dynamic, writer);
    } else if (mode === 'fixed') {
      emitFixed(data, writer);
    } else {
      emitStored(data, writer);
    }
    return writer.finish();
  }

  function build(files) {
    // files: [{name, data: string|Uint8Array}]
    const entries = files.map(f => {
      const bytes = typeof f.data === 'string' ? utf8(f.data) : f.data;
      const nameBytes = utf8(f.name);
      const compressed = deflateRaw(bytes);
      const method = compressed.length < bytes.length ? 8 : 0;
      return {
        name: f.name,
        bytes,
        nameBytes,
        crc: crc32(bytes),
        method,
        payload: method === 8 ? compressed : bytes,
      };
    });
    let offset = 0;
    const locals = [];
    for (const e of entries) {
      const lh = 30 + e.nameBytes.length;
      e.localOffset = offset;
      offset += lh + e.payload.length;
      locals.push({ e, header: lh });
    }
    const cdOffset = offset;
    let cdSize = 0;
    for (const e of entries) {
      const entry = 46 + e.nameBytes.length;
      cdSize += entry;
    }
    const total = cdOffset + cdSize + 22;
    const view = new DataView(new ArrayBuffer(total));
    const stamp = dosDateTime();
    let pos = 0;
    for (const e of entries) {
      u32be(view, pos, 0x04034b50);
      pos += 4; // local header
      u16be(view, pos, 20);
      pos += 2; // version needed
      u16be(view, pos, 0x0800);
      pos += 2; // flags: UTF-8 names
      u16be(view, pos, e.method);
      pos += 2; // method: 0 = store, 8 = deflate
      u16be(view, pos, stamp.time);
      pos += 2; // mod time
      u16be(view, pos, stamp.date);
      pos += 2; // mod date
      u32be(view, pos, e.crc);
      pos += 4;
      u32be(view, pos, e.payload.length);
      pos += 4; // compressed size
      u32be(view, pos, e.bytes.length);
      pos += 4; // uncompressed size
      u16be(view, pos, e.nameBytes.length);
      pos += 2;
      u16be(view, pos, 0);
      pos += 2; // extra len
      for (let i = 0; i < e.nameBytes.length; i++) {
        view.setUint8(pos++, e.nameBytes[i]);
      }
      for (let i = 0; i < e.payload.length; i++) {
        view.setUint8(pos++, e.payload[i]);
      }
    }
    // central directory
    let cdPos = offset;
    for (const e of entries) {
      u32be(view, cdPos, 0x02014b50);
      cdPos += 4;
      u16be(view, cdPos, 20);
      cdPos += 2;
      u16be(view, cdPos, 20);
      cdPos += 2;
      u16be(view, cdPos, 0x0800);
      cdPos += 2;
      u16be(view, cdPos, e.method);
      cdPos += 2;
      u16be(view, cdPos, stamp.time);
      cdPos += 2; // mod time
      u16be(view, cdPos, stamp.date);
      cdPos += 2; // mod date
      u32be(view, cdPos, e.crc);
      cdPos += 4;
      u32be(view, cdPos, e.payload.length);
      cdPos += 4;
      u32be(view, cdPos, e.bytes.length);
      cdPos += 4;
      u16be(view, cdPos, e.nameBytes.length);
      cdPos += 2;
      u16be(view, cdPos, 0);
      cdPos += 2; // extra
      u16be(view, cdPos, 0);
      cdPos += 2; // comment
      u16be(view, cdPos, 0);
      cdPos += 2; // disk start
      u16be(view, cdPos, 0);
      cdPos += 2; // internal attrs
      u32be(view, cdPos, 0);
      cdPos += 4; // external attrs
      u32be(view, cdPos, e.localOffset);
      cdPos += 4;
      for (let i = 0; i < e.nameBytes.length; i++) {
        view.setUint8(cdPos++, e.nameBytes[i]);
      }
    }
    // EOCD
    u32be(view, cdPos, 0x06054b50);
    cdPos += 4;
    u16be(view, cdPos, 0);
    cdPos += 2; // disk
    u16be(view, cdPos, 0);
    cdPos += 2; // start disk
    u16be(view, cdPos, entries.length);
    cdPos += 2;
    u16be(view, cdPos, entries.length);
    cdPos += 2;
    u32be(view, cdPos, cdSize);
    cdPos += 4;
    u32be(view, cdPos, cdOffset);
    cdPos += 4;
    u16be(view, cdPos, 0);
    cdPos += 2; // comment len
    return view.buffer;
  }

  // ---------------------------------------------------------------------------
  // Lector
  // ---------------------------------------------------------------------------

  const DEFAULT_LIMITS = Object.freeze({
    maxEntries: 100000,
    maxTotalBytes: 1024 * 1024 * 1024,
    maxEntryBytes: 256 * 1024 * 1024,
    maxCompressionRatio: 500,
    ratioThresholdBytes: 1024 * 1024,
  });

  class ZipError extends Error {
    constructor(message) {
      super(message);
      this.name = 'ZipError';
    }
  }

  function u16(bytes, off) {
    return bytes[off] | (bytes[off + 1] << 8);
  }

  function u32(bytes, off) {
    return (bytes[off] | (bytes[off + 1] << 8) | (bytes[off + 2] << 16) | (bytes[off + 3] << 24)) >>> 0;
  }

  function decodeName(bytes) {
    if (typeof TextDecoder === 'undefined') {
      throw new ZipError('TextDecoder is not available to decode ZIP names');
    }
    return new TextDecoder('utf-8').decode(bytes);
  }

  function findEocd(bytes) {
    const minimum = Math.max(0, bytes.length - (22 + 0xffff));
    for (let pos = bytes.length - 22; pos >= minimum; pos--) {
      if (u32(bytes, pos) !== 0x06054b50) {
        continue;
      }
      const commentLength = u16(bytes, pos + 20);
      if (pos + 22 + commentLength <= bytes.length) {
        return pos;
      }
    }
    throw new ZipError('not a ZIP archive: end of central directory not found');
  }

  function hasZip64Locator(bytes, eocd) {
    const at = eocd - 20;
    return at >= 0 && u32(bytes, at) === 0x07064b50;
  }

  function parseCentralDirectory(bytes, limits) {
    const eocd = findEocd(bytes);
    const disk = u16(bytes, eocd + 4);
    const centralDisk = u16(bytes, eocd + 6);
    const entriesOnDisk = u16(bytes, eocd + 8);
    const totalEntries = u16(bytes, eocd + 10);
    const centralSize = u32(bytes, eocd + 12);
    const centralOffset = u32(bytes, eocd + 16);

    if (disk !== 0 || centralDisk !== 0 || entriesOnDisk !== totalEntries) {
      throw new ZipError('multi-disk ZIP archives are not supported');
    }
    if (hasZip64Locator(bytes, eocd)) {
      throw new ZipError('ZIP64 archives are not supported');
    }
    if (centralSize === 0xffffffff || centralOffset === 0xffffffff) {
      throw new ZipError('ZIP64 archives are not supported');
    }
    if (totalEntries > limits.maxEntries) {
      throw new ZipError('ZIP has ' + totalEntries + ' entries; the limit is ' + limits.maxEntries);
    }
    if (centralOffset + centralSize > eocd) {
      throw new ZipError('central directory is out of bounds');
    }

    const entries = [];
    const names = new Set();
    let pos = centralOffset;
    let totalBytes = 0;

    for (let index = 0; index < totalEntries; index++) {
      if (pos + 46 > centralOffset + centralSize) {
        throw new ZipError('truncated central directory');
      }
      if (u32(bytes, pos) !== 0x02014b50) {
        throw new ZipError('invalid central directory signature');
      }
      const flags = u16(bytes, pos + 8);
      const method = u16(bytes, pos + 10);
      const crc = u32(bytes, pos + 16);
      const compressedSize = u32(bytes, pos + 20);
      const uncompressedSize = u32(bytes, pos + 24);
      const nameLength = u16(bytes, pos + 28);
      const extraLength = u16(bytes, pos + 30);
      const commentLength = u16(bytes, pos + 32);
      const externalAttributes = u32(bytes, pos + 38);
      const localOffset = u32(bytes, pos + 42);
      const end = pos + 46 + nameLength + extraLength + commentLength;
      if (end > centralOffset + centralSize) {
        throw new ZipError('truncated central directory entry');
      }
      if ((flags & 0x0001) !== 0) {
        throw new ZipError('encrypted ZIP entries are not supported');
      }
      if (method !== 0 && method !== 8) {
        throw new ZipError('unsupported compression method ' + method);
      }
      if (method === 0 && compressedSize !== uncompressedSize) {
        throw new ZipError('stored entry sizes do not match');
      }
      if (uncompressedSize > limits.maxEntryBytes) {
        throw new ZipError(
          "entry '" +
            decodeName(bytes.subarray(pos + 46, pos + 46 + nameLength)) +
            "' exceeds the per-file limit of " +
            limits.maxEntryBytes +
            ' bytes'
        );
      }
      totalBytes += uncompressedSize;
      if (totalBytes > limits.maxTotalBytes) {
        throw new ZipError('ZIP exceeds the total uncompressed limit of ' + limits.maxTotalBytes + ' bytes');
      }
      if (uncompressedSize > limits.ratioThresholdBytes) {
        if (compressedSize === 0) {
          throw new ZipError('compressed size is zero for a non-empty entry');
        }
        if (uncompressedSize / compressedSize > limits.maxCompressionRatio) {
          throw new ZipError('entry exceeds the compression ratio limit of ' + limits.maxCompressionRatio + ':1');
        }
      }

      const filename = decodeName(bytes.subarray(pos + 46, pos + 46 + nameLength));
      if (filename.indexOf('\0') >= 0) {
        throw new ZipError('ZIP entry name contains a NUL byte');
      }
      if (names.has(filename)) {
        throw new ZipError("duplicate ZIP entry '" + filename + "'");
      }
      names.add(filename);

      entries.push(
        new ZipEntry(bytes, {
          filename,
          flags,
          method,
          crc,
          compressedSize,
          uncompressedSize,
          externalAttributes,
          localOffset,
        })
      );
      pos = end;
    }
    return entries;
  }

  class ZipEntry {
    constructor(bytes, fields) {
      this.bytes = bytes;
      this.filename = fields.filename;
      this.flags = fields.flags;
      this.method = fields.method;
      this.crc32 = fields.crc;
      this.compressedSize = fields.compressedSize;
      this.uncompressedSize = fields.uncompressedSize;
      this.externalAttributes = fields.externalAttributes;
      this.localOffset = fields.localOffset;
      this._data = null;
    }

    get isDirectory() {
      return this.filename.endsWith('/');
    }

    get isSymlink() {
      const mode = (this.externalAttributes >>> 16) & 0xffff;
      return (mode & 0xf000) === 0xa000;
    }

    getData() {
      if (this._data) {
        return this._data;
      }
      if (this.isDirectory) {
        this._data = new Uint8Array(0);
        return this._data;
      }

      const bytes = this.bytes;
      const offset = this.localOffset;
      if (offset + 30 > bytes.length) {
        throw new ZipError("local header for '" + this.filename + "' is out of bounds");
      }
      if (u32(bytes, offset) !== 0x04034b50) {
        throw new ZipError("invalid local header signature for '" + this.filename + "'");
      }
      const flags = u16(bytes, offset + 6);
      const method = u16(bytes, offset + 8);
      if ((flags & 0x0001) !== 0) {
        throw new ZipError('encrypted ZIP entries are not supported');
      }
      if (method !== this.method) {
        throw new ZipError("local header method does not match the central directory for '" + this.filename + "'");
      }
      const nameLength = u16(bytes, offset + 26);
      const extraLength = u16(bytes, offset + 28);
      const start = offset + 30 + nameLength + extraLength;
      const end = start + this.compressedSize;
      if (end > bytes.length) {
        throw new ZipError("entry data for '" + this.filename + "' is truncated");
      }

      const raw = bytes.subarray(start, end);
      const data = this.method === 0 ? raw.slice() : inflateRaw(raw);
      if (data.length !== this.uncompressedSize) {
        throw new ZipError("uncompressed size does not match the central directory for '" + this.filename + "'");
      }
      if (crc32(data) !== this.crc32) {
        throw new ZipError("CRC mismatch in '" + this.filename + "'");
      }
      this._data = data;
      return data;
    }
  }

  class Reader {
    constructor(input, options) {
      const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
      const opts = options || {};
      this.bytes = bytes;
      this.limits = Object.assign({}, DEFAULT_LIMITS, opts.limits || opts);
      this.entries = parseCentralDirectory(bytes, this.limits);
    }

    [Symbol.iterator]() {
      return this.entries[Symbol.iterator]();
    }
  }

  // ---------------------------------------------------------------------------
  // Inflate (RFC 1951) — síncrono y sin dependencias
  // ---------------------------------------------------------------------------

  const LENGTH_BASE = [
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
  ];
  const LENGTH_EXTRA = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0];
  const DISTANCE_BASE = [
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
    8193, 12289, 16385, 24577,
  ];
  const DISTANCE_EXTRA = [
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
  ];
  const CODE_LENGTH_ORDER = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];

  function BitReader(bytes) {
    this.bytes = bytes;
    this.pos = 0;
    this.buffer = 0;
    this.count = 0;
  }

  BitReader.prototype.bits = function (n) {
    if (n === 0) {
      return 0;
    }
    while (this.count < n) {
      if (this.pos >= this.bytes.length) {
        throw new ZipError('unexpected end of DEFLATE stream');
      }
      this.buffer |= this.bytes[this.pos++] << this.count;
      this.count += 8;
    }
    const value = this.buffer & ((1 << n) - 1);
    this.buffer >>>= n;
    this.count -= n;
    return value;
  };

  BitReader.prototype.align = function () {
    this.buffer = 0;
    this.count = 0;
  };

  function buildHuffman(lengths) {
    const maxBits = 15;
    const counts = new Int32Array(maxBits + 1);
    for (let i = 0; i < lengths.length; i++) {
      counts[lengths[i]]++;
    }
    let left = 1;
    for (let len = 1; len <= maxBits; len++) {
      left <<= 1;
      left -= counts[len];
      if (left < 0) {
        throw new ZipError('over-subscribed Huffman code');
      }
    }
    const offsets = new Int32Array(maxBits + 1);
    for (let len = 1; len < maxBits; len++) {
      offsets[len + 1] = offsets[len] + counts[len];
    }
    const symbols = new Int32Array(lengths.length - counts[0]);
    for (let symbol = 0; symbol < lengths.length; symbol++) {
      if (lengths[symbol] !== 0) {
        symbols[offsets[lengths[symbol]]++] = symbol;
      }
    }
    return { counts, symbols };
  }

  function decodeSymbol(reader, huffman) {
    let code = 0;
    let first = 0;
    let index = 0;
    for (let len = 1; len <= 15; len++) {
      code |= reader.bits(1);
      const count = huffman.counts[len];
      if (code - count < first) {
        const symbol = huffman.symbols[index + (code - first)];
        if (symbol === undefined) {
          throw new ZipError('invalid Huffman code');
        }
        return symbol;
      }
      index += count;
      first += count;
      first <<= 1;
      code <<= 1;
    }
    throw new ZipError('invalid Huffman code');
  }

  function OutputBuffer(initial) {
    this.bytes = new Uint8Array(Math.max(1024, initial));
    this.length = 0;
  }

  OutputBuffer.prototype.ensure = function (extra) {
    if (this.length + extra <= this.bytes.length) {
      return;
    }
    let size = this.bytes.length;
    while (size < this.length + extra) {
      size *= 2;
    }
    const next = new Uint8Array(size);
    next.set(this.bytes.subarray(0, this.length));
    this.bytes = next;
  };

  OutputBuffer.prototype.push = function (value) {
    this.ensure(1);
    this.bytes[this.length++] = value;
  };

  OutputBuffer.prototype.copy = function (distance, length) {
    if (distance > this.length) {
      throw new ZipError('invalid DEFLATE distance');
    }
    this.ensure(length);
    let from = this.length - distance;
    for (let i = 0; i < length; i++) {
      this.bytes[this.length++] = this.bytes[from++];
    }
  };

  function inflateBlock(reader, output, literals, distances) {
    for (;;) {
      const symbol = decodeSymbol(reader, literals);
      if (symbol < 256) {
        output.push(symbol);
        continue;
      }
      if (symbol === 256) {
        return;
      }
      const lengthIndex = symbol - 257;
      if (lengthIndex < 0 || lengthIndex >= LENGTH_BASE.length) {
        throw new ZipError('invalid DEFLATE length symbol');
      }
      const length = LENGTH_BASE[lengthIndex] + reader.bits(LENGTH_EXTRA[lengthIndex]);
      const distanceSymbol = decodeSymbol(reader, distances);
      if (distanceSymbol >= DISTANCE_BASE.length) {
        throw new ZipError('invalid DEFLATE distance symbol');
      }
      const distance = DISTANCE_BASE[distanceSymbol] + reader.bits(DISTANCE_EXTRA[distanceSymbol]);
      output.copy(distance, length);
    }
  }

  const FIXED_LITERALS = (() => {
    const lengths = new Uint8Array(288);
    for (let i = 0; i <= 143; i++) {
      lengths[i] = 8;
    }
    for (let i = 144; i <= 255; i++) {
      lengths[i] = 9;
    }
    for (let i = 256; i <= 279; i++) {
      lengths[i] = 7;
    }
    for (let i = 280; i <= 287; i++) {
      lengths[i] = 8;
    }
    return buildHuffman(lengths);
  })();

  const FIXED_DISTANCES = (() => {
    const lengths = new Uint8Array(32);
    lengths.fill(5);
    return buildHuffman(lengths);
  })();

  function dynamicTables(reader) {
    const literalCount = reader.bits(5) + 257;
    const distanceCount = reader.bits(5) + 1;
    const codeLengthCount = reader.bits(4) + 4;

    const codeLengths = new Uint8Array(19);
    for (let i = 0; i < codeLengthCount; i++) {
      codeLengths[CODE_LENGTH_ORDER[i]] = reader.bits(3);
    }
    const codeHuffman = buildHuffman(codeLengths);

    const lengths = new Uint8Array(literalCount + distanceCount);
    let index = 0;
    while (index < lengths.length) {
      const symbol = decodeSymbol(reader, codeHuffman);
      if (symbol < 16) {
        lengths[index++] = symbol;
      } else if (symbol === 16) {
        if (index === 0) {
          throw new ZipError('repeat code without a previous length');
        }
        const repeat = reader.bits(2) + 3;
        if (index + repeat > lengths.length) {
          throw new ZipError('code length repeat overflows the table');
        }
        lengths.fill(lengths[index - 1], index, index + repeat);
        index += repeat;
      } else if (symbol === 17) {
        const repeat = reader.bits(3) + 3;
        if (index + repeat > lengths.length) {
          throw new ZipError('code length repeat overflows the table');
        }
        index += repeat;
      } else if (symbol === 18) {
        const repeat = reader.bits(7) + 11;
        if (index + repeat > lengths.length) {
          throw new ZipError('code length repeat overflows the table');
        }
        index += repeat;
      } else {
        throw new ZipError('invalid code length symbol');
      }
    }

    return {
      literals: buildHuffman(lengths.subarray(0, literalCount)),
      distances: buildHuffman(lengths.subarray(literalCount)),
    };
  }

  function inflateRaw(input) {
    const reader = new BitReader(input);
    const output = new OutputBuffer(input.length * 3);
    let final = 0;

    while (!final) {
      final = reader.bits(1);
      const type = reader.bits(2);
      if (type === 0) {
        reader.align();
        if (reader.pos + 4 > input.length) {
          throw new ZipError('truncated stored block');
        }
        const length = input[reader.pos] | (input[reader.pos + 1] << 8);
        const complement = input[reader.pos + 2] | (input[reader.pos + 3] << 8);
        if ((length ^ 0xffff) !== complement) {
          throw new ZipError('invalid stored block length');
        }
        reader.pos += 4;
        if (reader.pos + length > input.length) {
          throw new ZipError('truncated stored block');
        }
        output.ensure(length);
        output.bytes.set(input.subarray(reader.pos, reader.pos + length), output.length);
        output.length += length;
        reader.pos += length;
      } else if (type === 1) {
        inflateBlock(reader, output, FIXED_LITERALS, FIXED_DISTANCES);
      } else if (type === 2) {
        const tables = dynamicTables(reader);
        inflateBlock(reader, output, tables.literals, tables.distances);
      } else {
        throw new ZipError('invalid DEFLATE block type');
      }
    }
    return output.bytes.subarray(0, output.length);
  }

  global.Suite = global.Suite || {};
  global.Suite.zip = {
    build,
    Reader,
    ZipError,
    DEFAULT_LIMITS,
    deflateRaw,
    inflateRaw,
  };
})(window || this);

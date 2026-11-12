/* zip.js — escritor y lector ZIP sin dependencias.
 *
 * El escritor usa STORE y fecha fija para que la exportación sea determinista.
 * El lector implementa directorio central, STORE (0), DEFLATE (8), CRC-32,
 * descriptor de datos y límites anti-bomba. No soporta cifrado, multi-disco ni
 * ZIP64: falla con un error explícito en lugar de aceptar un archivo dudoso. */
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

  function build(files) {
    // files: [{name, data: string|Uint8Array}]
    const entries = files.map(f => {
      const bytes = typeof f.data === 'string' ? utf8(f.data) : f.data;
      const nameBytes = utf8(f.name);
      return { name: f.name, bytes, nameBytes, crc: crc32(bytes) };
    });
    let offset = 0;
    const locals = [];
    for (const e of entries) {
      const lh = 30 + e.nameBytes.length;
      e.localOffset = offset;
      offset += lh + e.bytes.length;
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
      u16be(view, pos, 0);
      pos += 2; // method: store
      u16be(view, pos, stamp.time);
      pos += 2; // mod time
      u16be(view, pos, stamp.date);
      pos += 2; // mod date
      u32be(view, pos, e.crc);
      pos += 4;
      u32be(view, pos, e.bytes.length);
      pos += 4; // compressed (store = same)
      u32be(view, pos, e.bytes.length);
      pos += 4; // uncompressed
      u16be(view, pos, e.nameBytes.length);
      pos += 2;
      u16be(view, pos, 0);
      pos += 2; // extra len
      for (let i = 0; i < e.nameBytes.length; i++) {
        view.setUint8(pos++, e.nameBytes[i]);
      }
      for (let i = 0; i < e.bytes.length; i++) {
        view.setUint8(pos++, e.bytes[i]);
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
      u16be(view, cdPos, 0);
      cdPos += 2;
      u16be(view, cdPos, stamp.time);
      cdPos += 2; // mod time
      u16be(view, cdPos, stamp.date);
      cdPos += 2; // mod date
      u32be(view, cdPos, e.crc);
      cdPos += 4;
      u32be(view, cdPos, e.bytes.length);
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
    inflateRaw,
  };
})(window || this);

/* zip.js — escritor ZIP método STORE (sin compresión), sin dependencias.
 * Prácticamente puro "contenedor" de archivos para tas download del ZIP. */
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
    const cdEntries = [];
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

  global.Suite = global.Suite || {};
  global.Suite.zip = { build };
})(window || this);

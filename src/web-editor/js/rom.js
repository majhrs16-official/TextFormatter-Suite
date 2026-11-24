/* rom.js — gestión de las ROM de iFlow (`policy.flow` y `policy.wasm`).
 *
 * El editor no compila ni desensambla todavía: valida el contenedor antes de
 * aceptar un archivo, calcula el SHA-256 del `.wasm` y mantiene el sidecar que
 * exige `WasmPolicyRouter`. Todo es síncrono y sin dependencias para que la
 * exportación pueda abortar antes de escribir bytes inválidos.
 *
 * Formato `.flow` v2 (ProgramImageParser): cabecera little-endian de 32 bytes
 * con layout ROM2, TOC de 16 bytes por entrada, segmentos tipados (BIOS,
 * PROGRAM, CONSTANTS y metadatos), payload copiado a memoria y un trailer
 * SHA-256 de 32 bytes sobre todo lo anterior. `FLOW` y `ROM2` son firmas de
 * bytes ASCII, no enteros multibyte.
 *
 * Formato `.wasm`: magic `\0asm`, versión 1 little-endian y secciones con
 * longitud varuint32. El editor no valida la semántica del módulo; solo la
 * cabecera y que las longitudes no se salgan del archivo. */
(function (global) {
  'use strict';

  const PATHS = Object.freeze({
    vm: 'config/iflow-16/policy.flow',
    wasm: 'config/iflow-wasm/policy.wasm',
    sidecar: 'config/iflow-wasm/policy.sha256',
  });

  const FLOW = Object.freeze({
    MAGIC: 'FLOW',
    LAYOUT_MAGIC: 'ROM2',
    HEADER_SIZE: 32,
    TOC_ENTRY_SIZE: 16,
    MAX_TOC_ENTRIES: 16,
    FORMAT_VERSION: 2,
    ISA_VERSION: 2,
    ABI_VERSION: 2,
    FIRMWARE_VERSION: 1,
    PAGE_SIZE: 0x1000,
    IMAGE_END: 0x3000,
    CONSTANTS_BASE: 0x2000,
    CONSTANTS_MAX_SIZE: 4096,
    HASH_SIZE: 32,
    FLAG_READ: 1,
    FLAG_WRITE: 2,
    FLAG_EXECUTE: 4,
    FLAG_USER: 8,
    SEGMENT_BIOS: 1,
    SEGMENT_BOOTLOADER: 2,
    SEGMENT_KERNEL: 3,
    SEGMENT_PROGRAM: 4,
    SEGMENT_CONSTANTS: 5,
    SEGMENT_CAPABILITIES: 6,
    SEGMENT_DEBUG: 7,
  });

  const WASM = Object.freeze({
    MAGIC: [0x00, 0x61, 0x73, 0x6d],
    VERSION: 1,
    HEADER_SIZE: 8,
  });

  const SHA256_RE = /[a-fA-F0-9]{64}/;
  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const B64_INDEX = (() => {
    const index = new Int16Array(128).fill(-1);
    for (let i = 0; i < B64.length; i++) {
      index[B64.charCodeAt(i)] = i;
    }
    return index;
  })();

  class RomError extends Error {
    constructor(message) {
      super(message);
      this.name = 'RomError';
    }
  }

  function fail(message) {
    throw new RomError(message);
  }

  /* ── SHA-256 (síncrono, sin WebCrypto) ─────────────────────── */

  const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98,
    0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8,
    0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
    0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
    0xc67178f2,
  ]);

  function rotr(value, bits) {
    return (value >>> bits) | (value << (32 - bits));
  }

  function sha256(bytes) {
    const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const length = data.length;
    const paddedLength = (((length + 9 + 63) >> 6) << 6) >>> 0;
    const padded = new Uint8Array(paddedLength);
    padded.set(data);
    padded[length] = 0x80;
    const view = new DataView(padded.buffer);
    const bitLengthHi = Math.floor(length / 0x20000000) >>> 0;
    const bitLengthLo = (length << 3) >>> 0;
    view.setUint32(paddedLength - 8, bitLengthHi);
    view.setUint32(paddedLength - 4, bitLengthLo);

    const hash = new Uint32Array([
      0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ]);
    const w = new Uint32Array(64);

    for (let offset = 0; offset < paddedLength; offset += 64) {
      for (let i = 0; i < 16; i++) {
        w[i] = view.getUint32(offset + i * 4);
      }
      for (let i = 16; i < 64; i++) {
        const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
        const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
      }

      let a = hash[0];
      let b = hash[1];
      let c = hash[2];
      let d = hash[3];
      let e = hash[4];
      let f = hash[5];
      let g = hash[6];
      let h = hash[7];

      for (let i = 0; i < 64; i++) {
        const s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        const ch = (e & f) ^ (~e & g);
        const t1 = (h + s1 + ch + K[i] + w[i]) >>> 0;
        const s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        const maj = (a & b) ^ (a & c) ^ (b & c);
        const t2 = (s0 + maj) >>> 0;
        h = g;
        g = f;
        f = e;
        e = (d + t1) >>> 0;
        d = c;
        c = b;
        b = a;
        a = (t1 + t2) >>> 0;
      }

      hash[0] = (hash[0] + a) >>> 0;
      hash[1] = (hash[1] + b) >>> 0;
      hash[2] = (hash[2] + c) >>> 0;
      hash[3] = (hash[3] + d) >>> 0;
      hash[4] = (hash[4] + e) >>> 0;
      hash[5] = (hash[5] + f) >>> 0;
      hash[6] = (hash[6] + g) >>> 0;
      hash[7] = (hash[7] + h) >>> 0;
    }

    const out = new Uint8Array(32);
    const outView = new DataView(out.buffer);
    for (let i = 0; i < 8; i++) {
      outView.setUint32(i * 4, hash[i]);
    }
    return out;
  }

  function hexOf(bytes) {
    let out = '';
    for (let i = 0; i < bytes.length; i++) {
      out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
    }
    return out;
  }

  function sha256Hex(bytes) {
    return hexOf(sha256(bytes));
  }

  /* ── base64 ────────────────────────────────────────────────── */

  function toBase64(bytes) {
    const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    let out = '';
    for (let i = 0; i < data.length; i += 3) {
      const a = data[i];
      const b = i + 1 < data.length ? data[i + 1] : 0;
      const c = i + 2 < data.length ? data[i + 2] : 0;
      out += B64[a >> 2];
      out += B64[((a & 0x03) << 4) | (b >> 4)];
      out += i + 1 < data.length ? B64[((b & 0x0f) << 2) | (c >> 6)] : '=';
      out += i + 2 < data.length ? B64[c & 0x3f] : '=';
    }
    return out;
  }

  function fromBase64(text) {
    if (typeof text !== 'string') {
      fail('los datos de la ROM deben ser base64');
    }
    const clean = text.replace(/[\s]/g, '');
    if (clean.length % 4 !== 0 || /[^A-Za-z0-9+/=]/.test(clean)) {
      fail('base64 inválido en la ROM');
    }
    const padding = clean.endsWith('==') ? 2 : clean.endsWith('=') ? 1 : 0;
    const out = new Uint8Array((clean.length / 4) * 3 - padding);
    let offset = 0;
    for (let i = 0; i < clean.length; i += 4) {
      const c0 = B64_INDEX[clean.charCodeAt(i)];
      const c1 = B64_INDEX[clean.charCodeAt(i + 1)];
      const c2 = clean[i + 2] === '=' ? 0 : B64_INDEX[clean.charCodeAt(i + 2)];
      const c3 = clean[i + 3] === '=' ? 0 : B64_INDEX[clean.charCodeAt(i + 3)];
      if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) {
        fail('base64 inválido en la ROM');
      }
      out[offset++] = (c0 << 2) | (c1 >> 4);
      if (offset < out.length) {
        out[offset++] = ((c1 & 0x0f) << 4) | (c2 >> 2);
      }
      if (offset < out.length) {
        out[offset++] = ((c2 & 0x03) << 6) | c3;
      }
    }
    return out;
  }

  /* ── sidecar SHA-256 ───────────────────────────────────────── */

  function sidecarHash(text) {
    if (typeof text !== 'string') {
      return null;
    }
    const match = SHA256_RE.exec(text);
    return match ? match[0].toLowerCase() : null;
  }

  function formatSidecar(hash, basename) {
    if (!SHA256_RE.test(hash)) {
      fail('hash SHA-256 inválido para el sidecar');
    }
    return hash.toLowerCase() + '  ' + (basename || 'policy.wasm') + '\n';
  }

  function sidecarPathFor(path) {
    return path.replace(/\.wasm$/i, '.sha256');
  }

  /* ── validación `.flow` ────────────────────────────────────── */

  function validateFlow(bytes) {
    const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const fail = message => ({ ok: false, error: message, meta: null });
    if (data.length < FLOW.HEADER_SIZE + FLOW.HASH_SIZE) {
      return fail('datos demasiado cortos para la cabecera .flow y el hash');
    }
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    const magic = String.fromCharCode(data[0], data[1], data[2], data[3]);
    if (magic !== FLOW.MAGIC) {
      return fail('no es un .flow (magic ' + JSON.stringify(magic) + ', se espera FLOW)');
    }
    const formatVersion = data[4];
    const isaVersion = data[5];
    const abiVersion = data[6];
    const firmwareVersion = data[7];
    if (formatVersion !== FLOW.FORMAT_VERSION) {
      return fail('versión de formato .flow no soportada: ' + formatVersion);
    }
    if (isaVersion > FLOW.ISA_VERSION) {
      return fail('versión de ISA demasiado nueva: ' + isaVersion);
    }
    if (abiVersion !== FLOW.ABI_VERSION) {
      return fail('ABI incompatible: ' + abiVersion + ' (se espera ' + FLOW.ABI_VERSION + ')');
    }
    if (firmwareVersion > FLOW.FIRMWARE_VERSION) {
      return fail('firmware mínimo demasiado nuevo: ' + firmwareVersion);
    }
    const layoutMagic = String.fromCharCode(data[16], data[17], data[18], data[19]);
    if (layoutMagic !== FLOW.LAYOUT_MAGIC) {
      return fail('layout .flow no soportado: ' + JSON.stringify(layoutMagic) + ' (se espera ROM2)');
    }

    const entryPoint = view.getUint16(8, true);
    const tocOffset = view.getUint16(10, true);
    const tocCount = view.getUint16(12, true);
    const headerFlags = view.getUint16(14, true);
    const payloadSize = view.getUint32(20, true);
    if (headerFlags !== 0) {
      return fail('flags de cabecera no soportados: 0x' + headerFlags.toString(16));
    }
    if (view.getUint32(24, true) !== 0 || view.getUint32(28, true) !== 0) {
      return fail('campos reservados de cabecera no nulos');
    }
    if (payloadSize + FLOW.HASH_SIZE > data.length) {
      return fail('payload .flow truncado: cabecera ' + payloadSize + ', archivo ' + (data.length - FLOW.HASH_SIZE));
    }
    if (payloadSize + FLOW.HASH_SIZE < data.length) {
      return fail('bytes extra después del hash: ' + (data.length - payloadSize - FLOW.HASH_SIZE));
    }
    if (tocOffset < FLOW.HEADER_SIZE) {
      return fail('el TOC se solapa con la cabecera');
    }
    if (tocCount < 1 || tocCount > FLOW.MAX_TOC_ENTRIES) {
      return fail('número de entradas TOC inválido: ' + tocCount);
    }
    const tocEnd = tocOffset + tocCount * FLOW.TOC_ENTRY_SIZE;
    if (tocEnd > payloadSize) {
      return fail('el TOC se extiende más allá del payload');
    }

    const toc = [];
    for (let i = 0; i < tocCount; i++) {
      const base = tocOffset + i * FLOW.TOC_ENTRY_SIZE;
      const type = data[base];
      if (type < 1 || type > 7) {
        return fail('tipo de segmento desconocido: 0x' + type.toString(16));
      }
      const flags = data[base + 1];
      if ((flags & ~(FLOW.FLAG_READ | FLOW.FLAG_WRITE | FLOW.FLAG_EXECUTE | FLOW.FLAG_USER)) !== 0) {
        return fail('flags de segmento desconocidos: 0x' + flags.toString(16));
      }
      const vaddr = view.getUint16(base + 2, true);
      const fileOffset = view.getUint32(base + 4, true);
      const size = view.getUint16(base + 8, true);
      const memSize = view.getUint16(base + 10, true);
      if (view.getUint32(base + 12, true) !== 0) {
        return fail('campos reservados de TOC no nulos');
      }
      if (fileOffset < tocEnd || fileOffset + size > payloadSize) {
        return fail('rango de archivo fuera del payload en TOC[' + i + ']');
      }
      toc.push({ type, flags, vaddr, fileOffset, size, memSize });
    }

    for (let i = 0; i < toc.length; i++) {
      const a = toc[i];
      for (let j = i + 1; j < toc.length; j++) {
        const b = toc[j];
        if (a.size > 0 && b.size > 0 && a.fileOffset < b.fileOffset + b.size && b.fileOffset < a.fileOffset + a.size) {
          return fail('rangos de archivo solapados en TOC[' + i + '] y TOC[' + j + ']');
        }
        if (
          a.type <= FLOW.SEGMENT_CONSTANTS &&
          b.type <= FLOW.SEGMENT_CONSTANTS &&
          a.memSize > 0 &&
          b.memSize > 0 &&
          a.vaddr < b.vaddr + b.memSize &&
          b.vaddr < a.vaddr + a.memSize
        ) {
          return fail('rangos virtuales solapados en TOC[' + i + '] y TOC[' + j + ']');
        }
      }
    }

    const counts = new Map();
    let program = null;
    let constants = null;
    let capabilitiesCount = 0;
    let debugLength = 0;
    let codeLength = 0;
    const codeFlags = FLOW.FLAG_READ | FLOW.FLAG_EXECUTE;
    for (const segment of toc) {
      counts.set(segment.type, (counts.get(segment.type) || 0) + 1);
      if (segment.type === FLOW.SEGMENT_CAPABILITIES || segment.type === FLOW.SEGMENT_DEBUG) {
        if (segment.vaddr !== 0 || segment.memSize !== 0 || segment.flags !== 0) {
          return fail('segmento de metadatos inválido');
        }
        if (segment.size === 0) {
          return fail('segmento de metadatos vacío');
        }
        if (segment.type === FLOW.SEGMENT_DEBUG) {
          debugLength = segment.size;
        } else {
          let start = 0;
          for (let i = segment.fileOffset; i < segment.fileOffset + segment.size; i++) {
            if (data[i] !== 0) {
              continue;
            }
            if (i === start + segment.fileOffset) {
              return fail('capacidad vacía');
            }
            capabilitiesCount++;
            start = i - segment.fileOffset + 1;
          }
          if (start !== segment.size) {
            return fail('capacidad sin terminador NUL');
          }
        }
        continue;
      }

      if (segment.vaddr % FLOW.PAGE_SIZE !== 0) {
        return fail('segmento no alineado a página');
      }
      if (segment.size === 0 || segment.memSize < segment.size) {
        return fail('tamaño de segmento inválido');
      }
      const region = segment.type === FLOW.SEGMENT_CONSTANTS ? 'imagen' : 'código';
      const limit = segment.type === FLOW.SEGMENT_CONSTANTS ? FLOW.IMAGE_END : FLOW.CONSTANTS_BASE;
      if (segment.vaddr + segment.memSize > limit) {
        return fail('segmento fuera de la región de ' + region);
      }
      if (
        segment.type === FLOW.SEGMENT_BIOS ||
        segment.type === FLOW.SEGMENT_BOOTLOADER ||
        segment.type === FLOW.SEGMENT_KERNEL
      ) {
        if ((segment.flags & codeFlags) !== codeFlags || (segment.flags & (FLOW.FLAG_WRITE | FLOW.FLAG_USER)) !== 0) {
          return fail('segmento de código con flags inválidos');
        }
        if (segment.type === FLOW.SEGMENT_BIOS && (segment.vaddr !== 0 || segment.memSize > FLOW.PAGE_SIZE)) {
          return fail('el BIOS debe ocupar la página 0');
        }
      } else if (segment.type === FLOW.SEGMENT_PROGRAM) {
        if (
          (segment.flags & (codeFlags | FLOW.FLAG_USER)) !== (codeFlags | FLOW.FLAG_USER) ||
          (segment.flags & FLOW.FLAG_WRITE) !== 0
        ) {
          return fail('PROGRAM debe ser R/X y de usuario');
        }
        program = segment;
      } else if (segment.type === FLOW.SEGMENT_CONSTANTS) {
        if (
          segment.vaddr !== FLOW.CONSTANTS_BASE ||
          segment.memSize !== segment.size ||
          (segment.size & 1) !== 0 ||
          segment.size > FLOW.CONSTANTS_MAX_SIZE ||
          segment.flags !== FLOW.FLAG_READ
        ) {
          return fail('segmento CONSTANTS inválido');
        }
        constants = segment;
      }
    }
    if ((counts.get(FLOW.SEGMENT_PROGRAM) || 0) !== 1 || !program) {
      return fail('la imagen debe tener exactamente un segmento PROGRAM');
    }
    for (const type of [
      FLOW.SEGMENT_BIOS,
      FLOW.SEGMENT_BOOTLOADER,
      FLOW.SEGMENT_KERNEL,
      FLOW.SEGMENT_CONSTANTS,
      FLOW.SEGMENT_CAPABILITIES,
      FLOW.SEGMENT_DEBUG,
    ]) {
      if ((counts.get(type) || 0) > 1) {
        return fail('segmento duplicado');
      }
    }
    if (program.size === 0) {
      return fail('el .flow no tiene código');
    }
    for (const segment of toc) {
      if (segment.type >= FLOW.SEGMENT_BIOS && segment.type <= FLOW.SEGMENT_PROGRAM) {
        codeLength = Math.max(codeLength, segment.vaddr + segment.memSize);
      }
    }
    const entryOk = toc.some(
      segment =>
        segment.type >= FLOW.SEGMENT_BIOS &&
        segment.type <= FLOW.SEGMENT_PROGRAM &&
        entryPoint >= segment.vaddr &&
        entryPoint < segment.vaddr + segment.size
    );
    if (!entryOk) {
      return fail('punto de entrada inválido: 0x' + entryPoint.toString(16));
    }

    const expected = data.slice(payloadSize, payloadSize + FLOW.HASH_SIZE);
    const actual = sha256(data.subarray(0, payloadSize));
    let same = true;
    for (let i = 0; i < FLOW.HASH_SIZE; i++) {
      if (expected[i] !== actual[i]) {
        same = false;
        break;
      }
    }
    if (!same) {
      return fail('el SHA-256 del .flow no coincide');
    }

    return {
      ok: true,
      error: null,
      meta: {
        formatVersion,
        isaVersion,
        abiVersion,
        firmwareVersion,
        entryPoint,
        codeLength,
        constantsLength: constants ? constants.size / 2 : 0,
        capabilitiesCount,
        debugLength,
        segmentCount: toc.length,
        hash: hexOf(expected),
      },
    };
  }

  /* ── validación `.wasm` ────────────────────────────────────── */

  function readVarUint(data, offset) {
    let result = 0;
    let shift = 0;
    for (let i = 0; i < 5; i++) {
      if (offset >= data.length) {
        return null;
      }
      const byte = data[offset++];
      result |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) {
        return { value: result >>> 0, offset };
      }
      shift += 7;
    }
    return null;
  }

  function validateWasm(bytes) {
    const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    if (data.length < WASM.HEADER_SIZE) {
      return { ok: false, error: 'datos demasiado cortos para la cabecera .wasm', meta: null };
    }
    for (let i = 0; i < 4; i++) {
      if (data[i] !== WASM.MAGIC[i]) {
        return { ok: false, error: 'no es un .wasm (magic inválido)', meta: null };
      }
    }
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    const version = view.getUint32(4, true);
    if (version !== WASM.VERSION) {
      return { ok: false, error: 'versión .wasm no soportada: ' + version, meta: null };
    }

    let offset = WASM.HEADER_SIZE;
    let sections = 0;
    while (offset < data.length) {
      const id = data[offset++];
      const size = readVarUint(data, offset);
      if (size === null) {
        return { ok: false, error: 'longitud de sección .wasm inválida', meta: null };
      }
      offset = size.offset;
      if (offset + size.value > data.length) {
        return {
          ok: false,
          error: 'sección .wasm ' + id + ' truncada: declara ' + size.value + ' bytes',
          meta: null,
        };
      }
      offset += size.value;
      sections++;
    }

    return {
      ok: true,
      error: null,
      meta: { version, sections, bytes: data.length, hash: sha256Hex(data) },
    };
  }

  function validate(kind, bytes) {
    if (kind === 'flow') {
      return validateFlow(bytes);
    }
    if (kind === 'wasm') {
      return validateWasm(bytes);
    }
    return { ok: false, error: 'tipo de ROM desconocido: ' + kind, meta: null };
  }

  /* ── rutas y entradas ──────────────────────────────────────── */

  function kindOfPath(path) {
    if (/\.flow$/i.test(path)) {
      return 'flow';
    }
    if (/\.wasm$/i.test(path)) {
      return 'wasm';
    }
    return null;
  }

  function isRomPath(path) {
    return (
      (path.startsWith('config/iflow-16/') && kindOfPath(path) === 'flow') ||
      (path.startsWith('config/iflow-wasm/') && kindOfPath(path) === 'wasm')
    );
  }

  function isSidecarPath(path) {
    return path.startsWith('config/iflow-wasm/') && /\.sha256$/i.test(path);
  }

  function canonicalPath(kind) {
    return kind === 'flow' ? PATHS.vm : kind === 'wasm' ? PATHS.wasm : null;
  }

  function basename(path) {
    return path.slice(path.lastIndexOf('/') + 1);
  }

  function clonePath(path, used) {
    const dot = path.lastIndexOf('.');
    const base = dot > path.lastIndexOf('/') ? path.slice(0, dot) : path;
    const ext = dot > path.lastIndexOf('/') ? path.slice(dot) : '';
    const taken = used instanceof Set ? used : new Set(used || []);
    let candidate = base + '-copy' + ext;
    let counter = 2;
    while (taken.has(candidate)) {
      candidate = base + '-copy-' + counter + ext;
      counter++;
    }
    return candidate;
  }

  function entryFromBytes(path, bytes, options) {
    const opts = options || {};
    const kind = kindOfPath(path);
    if (!kind) {
      fail('la ruta de ROM no termina en .flow o .wasm: ' + path);
    }
    const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const result = validate(kind, data);
    const entry = {
      id: opts.id || path,
      path,
      kind,
      canonical: path === canonicalPath(kind),
      data: toBase64(data),
      valid: result.ok,
      error: result.error,
      meta: result.meta,
      sidecarPath: null,
      sidecarText: null,
      sha256: null,
    };
    if (kind === 'wasm') {
      entry.sha256 = sha256Hex(data);
      entry.sidecarPath = opts.sidecarPath || sidecarPathFor(path);
      entry.sidecarText = opts.sidecarText === undefined ? null : opts.sidecarText;
      if (entry.valid && entry.sidecarText !== null) {
        const declared = sidecarHash(entry.sidecarText);
        if (declared === null) {
          entry.valid = false;
          entry.error = 'el sidecar no contiene un SHA-256';
        } else if (declared !== entry.sha256) {
          entry.valid = false;
          entry.error = 'el SHA-256 del sidecar no coincide con el .wasm';
        }
      } else if (entry.valid && entry.sidecarText === null && opts.requireSidecar) {
        entry.valid = false;
        entry.error = 'falta el sidecar SHA-256 del .wasm';
      }
    }
    return entry;
  }

  global.Suite = global.Suite || {};
  global.Suite.rom = {
    RomError,
    PATHS,
    FLOW,
    WASM,
    sha256,
    sha256Hex,
    toBase64,
    fromBase64,
    sidecarHash,
    formatSidecar,
    sidecarPathFor,
    validateFlow,
    validateWasm,
    validate,
    kindOfPath,
    isRomPath,
    isSidecarPath,
    canonicalPath,
    basename,
    clonePath,
    entryFromBytes,
  };
})(window || this);

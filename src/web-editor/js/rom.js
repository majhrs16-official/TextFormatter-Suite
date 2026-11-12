/* rom.js — gestión de las ROM de iFlow (`policy.flow` y `policy.wasm`).
 *
 * El editor no compila ni desensambla todavía: valida el contenedor antes de
 * aceptar un archivo, calcula el SHA-256 del `.wasm` y mantiene el sidecar que
 * exige `WasmPolicyRouter`. Todo es síncrono y sin dependencias para que la
 * exportación pueda abortar antes de escribir bytes inválidos.
 *
 * Formato `.flow` v2 (ProgramImageParser): cabecera big-endian de 20 bytes,
 * sección de código, constantes u16, capacidades terminadas en NUL, sección
 * debug opcional y un trailer SHA-256 de 32 bytes sobre todo lo anterior.
 *
 * Formato `.wasm`: magic `\0asm`, versión 1 little-endian y secciones con
 * longitud varuint32. El editor no valida la semántica del módulo; solo la
 * cabecera y que las longitudes no se salgan del archivo. */
(function (global) {
  'use strict';

  const PATHS = Object.freeze({
    vm: 'config/iflow-vm/policy.flow',
    wasm: 'config/iflow-wasm/policy.wasm',
    sidecar: 'config/iflow-wasm/policy.sha256',
  });

  const FLOW = Object.freeze({
    MAGIC: 0x464c4f57,
    HEADER_SIZE: 20,
    FORMAT_VERSION: 2,
    ISA_VERSION: 2,
    ABI_VERSION: 2,
    FIRMWARE_VERSION: 1,
    CODE_MAX_SIZE: 8192,
    CONSTANTS_MAX_SIZE: 4096,
    HASH_SIZE: 32,
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
    if (data.length < FLOW.HEADER_SIZE) {
      return { ok: false, error: 'datos demasiado cortos para la cabecera .flow', meta: null };
    }
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    const magic = view.getUint32(0);
    if (magic !== FLOW.MAGIC) {
      return {
        ok: false,
        error: 'no es un .flow (magic 0x' + magic.toString(16) + ', esperado 0x464c4f57)',
        meta: null,
      };
    }
    const formatVersion = data[4];
    const isaVersion = data[5];
    const abiVersion = data[6];
    const firmwareVersion = data[7];
    if (formatVersion !== FLOW.FORMAT_VERSION) {
      return { ok: false, error: 'versión de formato .flow no soportada: ' + formatVersion, meta: null };
    }
    if (isaVersion > FLOW.ISA_VERSION) {
      return { ok: false, error: 'versión de ISA demasiado nueva: ' + isaVersion, meta: null };
    }
    if (abiVersion !== FLOW.ABI_VERSION) {
      return {
        ok: false,
        error: 'ABI incompatible: ' + abiVersion + ' (se espera ' + FLOW.ABI_VERSION + ')',
        meta: null,
      };
    }
    if (firmwareVersion > FLOW.FIRMWARE_VERSION) {
      return { ok: false, error: 'firmware mínimo demasiado nuevo: ' + firmwareVersion, meta: null };
    }

    const entryPoint = view.getUint16(8);
    const codeLength = view.getUint16(10);
    const constantsLength = view.getUint16(12);
    const capabilitiesCount = data[14];
    const debugLength = view.getUint16(16);

    if (codeLength > FLOW.CODE_MAX_SIZE) {
      return { ok: false, error: 'sección de código demasiado grande: ' + codeLength + ' bytes', meta: null };
    }
    let offset = FLOW.HEADER_SIZE;
    if (offset + codeLength > data.length) {
      return { ok: false, error: 'sección de código truncada', meta: null };
    }
    offset += codeLength;

    if (constantsLength * 2 > FLOW.CONSTANTS_MAX_SIZE) {
      return {
        ok: false,
        error: 'sección de constantes demasiado grande: ' + constantsLength * 2 + ' bytes',
        meta: null,
      };
    }
    if (offset + constantsLength * 2 > data.length) {
      return { ok: false, error: 'sección de constantes truncada', meta: null };
    }
    offset += constantsLength * 2;

    for (let i = 0; i < capabilitiesCount; i++) {
      let terminated = false;
      while (offset < data.length) {
        if (data[offset] === 0) {
          offset++;
          terminated = true;
          break;
        }
        offset++;
      }
      if (!terminated) {
        return { ok: false, error: 'capacidad sin terminador NUL', meta: null };
      }
    }

    if (offset + debugLength > data.length) {
      return { ok: false, error: 'sección de depuración truncada', meta: null };
    }
    offset += debugLength;

    if (offset + FLOW.HASH_SIZE > data.length) {
      return { ok: false, error: 'falta el trailer SHA-256', meta: null };
    }
    if (offset + FLOW.HASH_SIZE !== data.length) {
      return {
        ok: false,
        error: 'bytes extra después del hash: ' + (data.length - offset - FLOW.HASH_SIZE),
        meta: null,
      };
    }

    const expected = data.slice(offset, offset + FLOW.HASH_SIZE);
    const actual = sha256(data.subarray(0, offset));
    let same = true;
    for (let i = 0; i < FLOW.HASH_SIZE; i++) {
      if (expected[i] !== actual[i]) {
        same = false;
        break;
      }
    }
    if (!same) {
      return { ok: false, error: 'el SHA-256 del .flow no coincide', meta: null };
    }
    if (codeLength === 0) {
      return { ok: false, error: 'el .flow no tiene código', meta: null };
    }
    if (entryPoint >= codeLength) {
      return {
        ok: false,
        error: 'punto de entrada inválido: 0x' + entryPoint.toString(16) + ' (código: ' + codeLength + ')',
        meta: null,
      };
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
        constantsLength,
        capabilitiesCount,
        debugLength,
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
      (path.startsWith('config/iflow-vm/') && kindOfPath(path) === 'flow') ||
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

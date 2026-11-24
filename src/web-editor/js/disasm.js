/* disasm.js — desensamblador de solo lectura para `.flow` v2.
 *
 * Decodifica únicamente el rango de usuario (>= 0x1000, o desde 0x0000 en
 * binarios bare-metal) y construye una CFG de bloques básicos. No reconstruye
 * el grafo de reglas del editor: la ROM no guarda ese grafo, así que se muestra
 * lo que dicen los bytes. La tabla de opcodes y tamaños debe coincidir con
 * `tools/iflow-compiler/src/types.ts` y `Opcode.java`; el test de regresión lo
 * comprueba.
 */
(function (global) {
  'use strict';

  const USER_ORIGIN = 0x1000;
  const HEADER_SIZE = 32;
  const TOC_ENTRY_SIZE = 16;
  const HASH_SIZE = 32;
  const MAGIC = 'FLOW';
  const LAYOUT_MAGIC = 'ROM2';
  const MAX_TOC_ENTRIES = 16;
  const EXECUTABLE = new Set([1, 2, 3, 4]);

  const OPCODES = {
    0x00: 'LI',
    0x01: 'MOV',
    0x02: 'LDB',
    0x03: 'LDW',
    0x04: 'STB',
    0x05: 'STW',
    0x06: 'COPY',
    0x07: 'MOVE',
    0x08: 'LDBI',
    0x09: 'LDWI',
    0x0a: 'STBI',
    0x0b: 'STWI',
    0x20: 'ADD',
    0x21: 'SUB',
    0x22: 'MUL',
    0x23: 'DIV',
    0x24: 'MOD',
    0x28: 'AND',
    0x29: 'OR',
    0x2a: 'XOR',
    0x2b: 'NOT',
    0x2c: 'SHL',
    0x2d: 'SHR',
    0x2e: 'SAR',
    0x30: 'CMP',
    0x31: 'CMPU',
    0x40: 'JMP',
    0x41: 'JZ',
    0x42: 'JNZ',
    0x43: 'JC',
    0x44: 'JV',
    0x48: 'CALL',
    0x49: 'RET',
    0x60: 'HOST',
    0x68: 'HALT',
    0x69: 'TRAP',
  };

  const SIZES = {
    0x00: 4,
    0x01: 2,
    0x02: 4,
    0x03: 4,
    0x04: 4,
    0x05: 4,
    0x06: 8,
    0x07: 8,
    0x08: 2,
    0x09: 2,
    0x0a: 2,
    0x0b: 2,
    0x20: 2,
    0x21: 2,
    0x22: 2,
    0x23: 2,
    0x24: 2,
    0x28: 2,
    0x29: 2,
    0x2a: 2,
    0x2b: 2,
    0x2c: 2,
    0x2d: 2,
    0x2e: 2,
    0x30: 2,
    0x31: 2,
    0x40: 3,
    0x41: 3,
    0x42: 3,
    0x43: 3,
    0x44: 3,
    0x48: 3,
    0x49: 1,
    0x60: 2,
    0x68: 1,
    0x69: 2,
  };

  const REGISTERS = ['R0', 'R1', 'R2', 'R3', 'R4', 'R5', 'R6', 'R7', 'IP', 'SP', 'FP', 'FL'];

  class DisasmError extends Error {
    constructor(message) {
      super(message);
      this.name = 'DisasmError';
    }
  }

  function u16(data, offset) {
    return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
  }

  function u32(data, offset) {
    return (
      (data[offset] & 0xff) |
      ((data[offset + 1] & 0xff) << 8) |
      ((data[offset + 2] & 0xff) << 16) |
      ((data[offset + 3] & 0xff) << 24)
    );
  }

  function hex(value, digits) {
    return '0x' + (value >>> 0).toString(16).toUpperCase().padStart(digits, '0');
  }

  function registerName(index) {
    return REGISTERS[index] || 'R?(' + index + ')';
  }

  function readCode(bytes) {
    if (!bytes || typeof bytes.length !== 'number' || typeof bytes.subarray !== 'function') {
      throw new DisasmError('datos demasiado cortos para la cabecera');
    }
    if (bytes.length < HEADER_SIZE + HASH_SIZE) {
      throw new DisasmError('datos demasiado cortos para la cabecera y el hash');
    }
    const magic = String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3]);
    if (magic !== MAGIC) {
      throw new DisasmError('no es un .flow (magic ' + hex(u32(bytes, 0), 8) + ')');
    }
    const layoutMagic = String.fromCharCode(bytes[16], bytes[17], bytes[18], bytes[19]);
    if (layoutMagic !== LAYOUT_MAGIC) {
      throw new DisasmError('layout .flow no soportado: ' + hex(u32(bytes, 16), 8));
    }
    const entryPoint = u16(bytes, 8);
    const tocOffset = u16(bytes, 10);
    const tocCount = u16(bytes, 12);
    const payloadSize = u32(bytes, 20) >>> 0;
    if (payloadSize !== bytes.length - HASH_SIZE) {
      throw new DisasmError('payload .flow truncado o incoherente');
    }
    if (tocOffset < HEADER_SIZE) {
      throw new DisasmError('el TOC se solapa con la cabecera');
    }
    if (tocCount < 1 || tocCount > MAX_TOC_ENTRIES) {
      throw new DisasmError('número de entradas TOC inválido: ' + tocCount);
    }
    const tocEnd = tocOffset + tocCount * TOC_ENTRY_SIZE;
    if (tocEnd > payloadSize) {
      throw new DisasmError('el TOC se extiende más allá del payload');
    }

    const segments = [];
    let end = 0;
    for (let i = 0; i < tocCount; i++) {
      const base = tocOffset + i * TOC_ENTRY_SIZE;
      const type = bytes[base];
      if (type < 1 || type > 7) {
        throw new DisasmError('tipo de segmento desconocido: 0x' + type.toString(16));
      }
      const flags = bytes[base + 1];
      if ((flags & ~0x0f) !== 0) {
        throw new DisasmError('flags de segmento desconocidos: 0x' + flags.toString(16));
      }
      const vaddr = u16(bytes, base + 2);
      const fileOffset = u32(bytes, base + 4) >>> 0;
      const size = u16(bytes, base + 8);
      const memSize = u16(bytes, base + 10);
      const reserved = u32(bytes, base + 12);
      if (reserved !== 0) {
        throw new DisasmError('campos reservados de TOC no nulos');
      }
      if (fileOffset < tocEnd || fileOffset + size > payloadSize) {
        throw new DisasmError('rango de archivo fuera del payload en TOC[' + i + ']');
      }
      if (EXECUTABLE.has(type)) {
        if (size === 0 || size > memSize) {
          throw new DisasmError('segmento ejecutable truncado en TOC[' + i + ']');
        }
        end = Math.max(end, vaddr + memSize);
      }
      segments.push({ type, vaddr, fileOffset, size });
    }

    const code = new Uint8Array(end);
    for (const segment of segments) {
      if (!EXECUTABLE.has(segment.type)) {
        continue;
      }
      code.set(bytes.subarray(segment.fileOffset, segment.fileOffset + segment.size), segment.vaddr);
    }
    return { entryPoint, code };
  }

  function decodeAt(code, address) {
    const opcode = code[address];
    const size = SIZES[opcode] || 0;
    if (size === 0) {
      return null;
    }
    if (address + size > code.length) {
      return {
        mnemonic: '.byte',
        operands: [hex(opcode, 2)],
        flow: 'halt',
        diagnostics: ['instrucción truncada'],
        size: 1,
      };
    }
    const diagnostics = [];
    const bytes = Array.from(code.subarray(address, address + size));
    const gpr = value => {
      if (value > 7) {
        diagnostics.push('registro no válido R' + value);
      }
      return registerName(value);
    };
    const packed = () => {
      const value = bytes[1];
      return [gpr((value >> 4) & 0x0f), gpr(value & 0x0f)];
    };

    switch (opcode) {
      case 0x00:
        return { mnemonic: 'LI', operands: [gpr(bytes[1]), hex(u16(bytes, 2), 4)], flow: 'next' };
      case 0x01:
      case 0x20:
      case 0x21:
      case 0x22:
      case 0x23:
      case 0x24:
      case 0x28:
      case 0x29:
      case 0x2a:
      case 0x2c:
      case 0x2d:
      case 0x2e:
      case 0x30:
      case 0x31:
        return { mnemonic: OPCODES[opcode], operands: packed(), flow: 'next' };
      case 0x08:
      case 0x09:
      case 0x0a:
      case 0x0b: {
        const operands = packed();
        return {
          mnemonic: OPCODES[opcode],
          operands: [operands[0], '[' + operands[1] + ']'],
          flow: 'next',
        };
      }
      case 0x02:
      case 0x03:
      case 0x04:
      case 0x05:
        return {
          mnemonic: OPCODES[opcode],
          operands: [gpr(bytes[1]), '[' + hex(u16(bytes, 2), 4) + ']'],
          flow: 'next',
        };
      case 0x06:
      case 0x07:
        return {
          mnemonic: OPCODES[opcode],
          operands: [hex(u16(bytes, 1), 4), hex(u16(bytes, 3), 4), hex(u16(bytes, 5), 4)],
          flow: 'next',
        };
      case 0x2b:
        return { mnemonic: 'NOT', operands: [gpr(bytes[1] & 0x0f)], flow: 'next' };
      case 0x40:
      case 0x41:
      case 0x42:
      case 0x43:
      case 0x44:
      case 0x48: {
        const raw = u16(bytes, 1);
        const signed = raw & 0x8000 ? raw - 0x10000 : raw;
        const target = address + size + signed;
        const flow = opcode === 0x40 ? 'jump' : opcode === 0x48 ? 'call' : 'branch';
        return { mnemonic: OPCODES[opcode], operands: [hex(target, 4)], flow, target };
      }
      case 0x49:
        return { mnemonic: 'RET', operands: [], flow: 'return' };
      case 0x60:
        return { mnemonic: 'HOST', operands: [], flow: 'next' };
      case 0x68:
        return { mnemonic: 'HALT', operands: [], flow: 'halt' };
      case 0x69:
        return { mnemonic: 'TRAP', operands: [hex(bytes[1], 2)], flow: 'halt' };
      default:
        return null;
    }
  }

  /**
   * Decodifica el rango de usuario de un `.flow` v2.
   * @param {Uint8Array} bytes
   * @param {{userStart?: number}} [options]
   */
  function decode(bytes, options) {
    const opts = options || {};
    const { entryPoint, code } = readCode(bytes);
    const end = code.length;
    let start;
    if (typeof opts.userStart === 'number') {
      if (opts.userStart < 0 || opts.userStart > end) {
        throw new DisasmError('userStart fuera del código: ' + opts.userStart);
      }
      start = opts.userStart;
    } else {
      start = end > USER_ORIGIN ? USER_ORIGIN : 0;
    }

    const diagnostics = [];
    const instructions = [];
    if (code.length > 0 && entryPoint >= code.length) {
      diagnostics.push({ address: entryPoint, message: 'entryPoint fuera del código' });
    }

    for (let address = start; address < end;) {
      const decoded = decodeAt(code, address);
      if (decoded === null) {
        const opcode = code[address];
        diagnostics.push({ address, message: 'opcode desconocido ' + hex(opcode, 2) });
        instructions.push({
          address,
          size: 1,
          bytes: [opcode],
          mnemonic: '.byte',
          operands: [hex(opcode, 2)],
          text: '.byte ' + hex(opcode, 2),
          flow: 'next',
        });
        address += 1;
        continue;
      }
      const size = decoded.size || SIZES[code[address]] || 1;
      for (const message of decoded.diagnostics || []) {
        diagnostics.push({ address, message });
      }
      const text = decoded.mnemonic + (decoded.operands.length > 0 ? ' ' + decoded.operands.join(', ') : '');
      instructions.push({
        address,
        size,
        bytes: Array.from(code.subarray(address, address + size)),
        mnemonic: decoded.mnemonic,
        operands: decoded.operands,
        text,
        target: decoded.target,
        flow: decoded.flow,
      });
      if (address + size > end) {
        diagnostics.push({ address, message: 'instrucción truncada al final del código' });
        break;
      }
      address += size;
    }

    const byAddress = new Map();
    for (const instruction of instructions) {
      byAddress.set(instruction.address, instruction);
    }
    const boundaries = new Set([end]);
    for (const instruction of instructions) {
      boundaries.add(instruction.address);
    }
    for (const instruction of instructions) {
      if (typeof instruction.target !== 'number') {
        continue;
      }
      if (instruction.target < 0 || instruction.target >= code.length) {
        diagnostics.push({
          address: instruction.address,
          message: 'salto fuera del código: ' + hex(instruction.target, 4),
        });
      } else if (instruction.target >= start && instruction.target < end && !boundaries.has(instruction.target)) {
        diagnostics.push({
          address: instruction.address,
          message: 'salto a un límite no decodificado: ' + hex(instruction.target, 4),
        });
      }
    }

    const leaders = new Set();
    if (instructions.length > 0) {
      leaders.add(instructions[0].address);
    }
    for (const instruction of instructions) {
      if (typeof instruction.target === 'number' && instruction.target >= start && instruction.target < end) {
        leaders.add(instruction.target);
      }
      const next = instruction.address + instruction.size;
      if (
        next < end &&
        (instruction.flow === 'jump' ||
          instruction.flow === 'branch' ||
          instruction.flow === 'call' ||
          instruction.flow === 'return' ||
          instruction.flow === 'halt')
      ) {
        leaders.add(next);
      }
    }
    const sorted = Array.from(leaders).sort((a, b) => a - b);

    const blocks = [];
    const blockStart = new Map();
    for (let i = 0; i < sorted.length; i++) {
      const blockBegin = sorted[i];
      const blockEnd = i + 1 < sorted.length ? sorted[i + 1] : end;
      const blockInstructions = [];
      for (let address = blockBegin; address < blockEnd;) {
        const instruction = byAddress.get(address);
        if (!instruction) {
          break;
        }
        blockInstructions.push(address);
        address += instruction.size;
      }
      const block = { start: blockBegin, end: blockEnd, instructions: blockInstructions, successors: [], exits: [] };
      blocks.push(block);
      blockStart.set(blockBegin, block);
    }

    for (const block of blocks) {
      const lastAddress = block.instructions[block.instructions.length - 1];
      const last = lastAddress === undefined ? undefined : byAddress.get(lastAddress);
      if (!last) {
        continue;
      }
      const next = last.address + last.size;
      const addTarget = target => {
        if (typeof target !== 'number') {
          return;
        }
        if (blockStart.has(target)) {
          if (!block.successors.includes(target)) {
            block.successors.push(target);
          }
        } else if (!block.exits.includes(target)) {
          block.exits.push(target);
        }
      };
      if (last.flow === 'jump') {
        addTarget(last.target);
      } else if (last.flow === 'branch' || last.flow === 'call') {
        addTarget(last.target);
        if (next < end) {
          addTarget(next);
        }
      } else if (last.flow !== 'return' && last.flow !== 'halt' && next < end) {
        addTarget(next);
      }
    }

    return {
      entryPoint,
      codeLength: code.length,
      userStart: start,
      userEnd: end,
      instructions,
      blocks,
      diagnostics,
    };
  }

  /** Listado legible equivalente al de `iflow-dis`. */
  function formatListing(result) {
    const lines = [];
    lines.push(
      '; entry ' +
        hex(result.entryPoint, 4) +
        ', code ' +
        result.codeLength +
        ' bytes, user ' +
        hex(result.userStart, 4) +
        '..' +
        hex(result.userEnd, 4)
    );
    lines.push('');
    for (const instruction of result.instructions) {
      const bytes = instruction.bytes.map(b => b.toString(16).toUpperCase().padStart(2, '0')).join(' ');
      lines.push(hex(instruction.address, 4) + '  ' + bytes.padEnd(24, ' ') + '  ' + instruction.text);
    }
    lines.push('');
    lines.push('; CFG');
    for (const block of result.blocks) {
      const successors = block.successors.length > 0 ? block.successors.map(a => hex(a, 4)).join(', ') : '—';
      const exits = block.exits.length > 0 ? ' exits ' + block.exits.map(a => hex(a, 4)).join(', ') : '';
      lines.push(hex(block.start, 4) + '..' + hex(block.end, 4) + '  → ' + successors + exits);
    }
    if (result.diagnostics.length > 0) {
      lines.push('');
      lines.push('; diagnósticos');
      for (const diagnostic of result.diagnostics) {
        lines.push(hex(diagnostic.address, 4) + '  ' + diagnostic.message);
      }
    }
    return lines.join('\n') + '\n';
  }

  global.Suite = global.Suite || {};
  global.Suite.disasm = {
    DisasmError,
    OPCODES,
    SIZES,
    decode,
    formatListing,
    readCode,
  };
})(window || this);

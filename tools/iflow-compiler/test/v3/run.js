#!/usr/bin/env node
/**
 * v3 assembler tests.
 *
 * Scope: instruction encoding, symbols and relative relocations, ROM v3
 * header/TOC/hash layout, PRG3 segment and explicit rejection paths.
 */
'use strict';

const assert = require('assert');
const crypto = require('crypto');
const {
  AssemblerV3,
  AssemblyError,
  InstructionForm,
  OperandSize,
  classifyInstruction,
  encodeInstruction,
} = require('../../dist/v3');

let passed = 0;
let failed = 0;

function test(name, fn) {
  try {
    fn();
    passed += 1;
    console.log(`PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(`  ${error.message}`);
    if (error.stack) {
      console.error(error.stack.split('\n').slice(1, 4).join('\n'));
    }
  }
}

function instruction(mnemonic, operands) {
  return { kind: 'instruction', mnemonic, operands, line: 1, column: 1 };
}

function reg(number) {
  return { kind: 'reg', number };
}

function mem(base, index, displacement) {
  return {
    kind: 'mem',
    base,
    index,
    displacement: displacement ?? { kind: 'number', value: 0 },
  };
}

function encode(
  mnemonic,
  operands,
  address = 0,
  resolve = expr => (expr.kind === 'number' ? expr.value : 0),
) {
  const diagnostics = [];
  const plan = classifyInstruction(
    instruction(mnemonic, operands),
    (message, line, column) => diagnostics.push({ message, line, column }),
  );
  assert.ok(plan, `instruction did not classify: ${diagnostics.map(d => d.message).join(', ')}`);
  return {
    plan,
    bytes: Buffer.from(encodeInstruction(plan, address, resolve)),
  };
}

function bytesOf(source, options = {}) {
  return new AssemblerV3().assemble(source, 'test.flowasm', options);
}

function minimalRom(extra = '') {
  return `
.section .bios
.org 0x00001000
.entry start
start:
  MOV R0, 0
  HALT
.section .boot
.org 0x80000000
.entry boot
boot:
  NOP
.section .kernel
.org 0x80001000
.entry kmain
kmain:
  NOP
${extra}
`;
}

function u16(buffer, offset) {
  return buffer[offset] | (buffer[offset + 1] << 8);
}

function u32(buffer, offset) {
  return (buffer[offset] | (buffer[offset + 1] << 8)
    | (buffer[offset + 2] << 16) | (buffer[offset + 3] << 24)) >>> 0;
}

test('encodes register MOV as three little-endian bytes', () => {
  const { bytes } = encode('MOV', [reg(1), reg(2)]);
  assert.deepStrictEqual([...bytes], [0x00, 0x04, 0x12]);
});

test('encodes ALU immediate with word size', () => {
  const { bytes } = encode(
    'ADD',
    [reg(3), { kind: 'expr', expr: { kind: 'number', value: 5 } }],
    0,
    () => 5);
  assert.deepStrictEqual([...bytes], [0x41, 0x04, 0x30, 0x05, 0x00, 0x00, 0x00]);
});

test('encodes relative JMP as PC + 7 + displacement', () => {
  const { bytes } = encode('JMP', [{ kind: 'expr', expr: { kind: 'symbol', name: 'target' } }],
    0x1000, () => 0x1010);
  assert.deepStrictEqual([...bytes], [0x81, 0x04, 0x00, 0x09, 0x00, 0x00, 0x00]);
});

test('encodes JZ through the Jcc condition nibble', () => {
  const { bytes } = encode('JZ', [{ kind: 'expr', expr: { kind: 'symbol', name: 'target' } }],
    0x1000, () => 0x1010);
  assert.deepStrictEqual([...bytes], [0x85, 0x04, 0x00, 0x09, 0x00, 0x00, 0x00]);
});

test('encodes LR.W load and SC.W store forms', () => {
  const lr = encode('LR.W', [reg(1), mem(2, null, { kind: 'number', value: 4 })]).bytes;
  assert.deepStrictEqual([...lr], [0xea, 0x24, 0x10, 0x04, 0x00, 0x00, 0x00]);

  const sc = encode('SC.W', [reg(1), reg(3), mem(2, null)]).bytes;
  assert.deepStrictEqual([...sc], [0xef, 0x24, 0x13, 0x00, 0x00, 0x00, 0x00]);
});

test('encodes MOV.B and MOV.H memory sizes', () => {
  const load = encode('MOV.B', [reg(1), mem(2, null)]).bytes;
  assert.deepStrictEqual([...load], [0x02, 0x20, 0x10, 0x00, 0x00, 0x00, 0x00]);
  const store = encode('MOV.H', [mem(2, 3), reg(1)]).bytes;
  assert.deepStrictEqual([...store], [0x03, 0x2a, 0x13, 0x00, 0x00, 0x00, 0x00]);
});

test('builds a valid three-segment ROM with hashes and reset vector', () => {
  const result = bytesOf(minimalRom());
  const rom = Buffer.from(result.bytes);
  assert.strictEqual(rom.subarray(0, 8).toString('ascii'), 'FLOWROM\0');
  assert.strictEqual(u16(rom, 8), 3);
  assert.strictEqual(u16(rom, 10), 32);
  assert.strictEqual(u16(rom, 12), 3);
  assert.strictEqual(u16(rom, 14), 80);
  assert.strictEqual(u32(rom, 20), rom.length);
  assert.strictEqual(u32(rom, 24), 0x1000);
  assert.strictEqual(u32(rom, 28), 80);
  assert.strictEqual(u16(rom, 32), 3);
  assert.strictEqual(u16(rom, 34), 64);
  assert.strictEqual(u32(rom, 36), 1);
  assert.strictEqual(result.segments.length, 3);
  assert.deepStrictEqual(result.segments.map(s => s.type), [1, 2, 3]);

  const expected = Buffer.from(rom);
  expected.fill(0, 48, 80);
  assert.strictEqual(
    crypto.createHash('sha256').update(expected).digest('hex'),
    rom.subarray(48, 80).toString('hex'));

  let previousOffset = 80 + 3 * 64;
  result.segments.forEach((segment, index) => {
    const toc = 80 + index * 64;
    const offset = u32(rom, toc + 4);
    assert.strictEqual(rom[toc], segment.type);
    assert.ok(offset >= 80 + 3 * 64);
    assert.ok(offset >= previousOffset);
    assert.strictEqual(offset % 16, 0);
    if (segment.type === 1) {
      assert.strictEqual(offset, segment.loadAddress,
        'BIOS must be mapped at its execution address');
    }
    previousOffset = offset + segment.bytes.length;
    assert.strictEqual(u32(rom, toc + 8), segment.bytes.length);
    assert.strictEqual(u32(rom, toc + 12), segment.loadAddress);
    assert.strictEqual(u32(rom, toc + 20), segment.memSize);
    assert.deepStrictEqual(
      rom.subarray(offset, offset + segment.bytes.length),
      Buffer.from(segment.bytes));
    assert.strictEqual(
      crypto.createHash('sha256').update(segment.bytes).digest('hex'),
      rom.subarray(toc + 24, toc + 56).toString('hex'));
  });
});

test('resolves labels, data words and align in the emitted bytes', () => {
  const source = `
.section .bios
.org 0x00001000
.entry start
start:
  JMP done
  .byte 0xaa
  .align 4
done:
  .word target
  .half 0x1234
  .string "ok"
target:
  NOP
.section .boot
.org 0x80000000
boot:
  NOP
.section .kernel
.org 0x80001000
kmain:
  NOP
`;
  const result = bytesOf(source);
  const bios = result.segments.find(s => s.type === 1).bytes;
  assert.strictEqual(bios[0], 0x81);
  assert.strictEqual(bios[3], 1);
  assert.strictEqual(bios[7], 0xaa);
  const target = result.symbols.find(s => s.name === 'target').address;
  assert.strictEqual(target, 0x1011);
  assert.strictEqual(u32(Buffer.from(bios), 8), target);
  assert.strictEqual(u16(Buffer.from(bios), 12), 0x1234);
  assert.strictEqual(Buffer.from(bios).subarray(14, 17).toString('ascii'), 'ok\0');
});

test('wraps a PROGRAM section in PRG3 with capabilities', () => {
  const result = bytesOf(minimalRom(`
.section .program.main
.org 0x02000000
.entry main
.capability "iflow.property.read"
main:
  NOP
`));
  const program = result.segments.find(s => s.type === 4);
  assert.ok(program);
  const bytes = Buffer.from(program.bytes);
  assert.strictEqual(bytes.subarray(0, 4).toString('ascii'), 'PRG3');
  assert.strictEqual(u32(bytes, 4), 32);
  assert.strictEqual(u32(bytes, 8), 35);
  assert.strictEqual(u32(bytes, 12), 0);
  assert.strictEqual(u32(bytes, 20), 35);
  assert.strictEqual(u16(bytes, 35), 1);
  assert.strictEqual(program.entryPoint, 0x02000000 + 32);
  assert.strictEqual(u32(bytes, 24), 2 + 2 + Buffer.byteLength('iflow.property.read'));
});

test('rejects a ROM without the required KERNEL segment', () => {
  assert.throws(() => bytesOf(`
.section .bios
.org 0x1000
start: NOP
.section .boot
.org 0x80000000
boot: NOP
`), error => error instanceof AssemblyError
    && /KERNEL/.test(error.message));
});

test('rejects bad registers, sizes, values and relocations', () => {
  assert.throws(() => bytesOf(minimalRom('\n  MOV R16, 1\n')),
    error => error instanceof AssemblyError);
  assert.throws(() => bytesOf(minimalRom('\n  ADD.B R1, R2\n')),
    error => error instanceof AssemblyError && /size suffix/.test(error.message));
  assert.throws(() => bytesOf(minimalRom('\n  .byte 300\n')),
    error => error instanceof AssemblyError && /out of range/.test(error.message));
  assert.throws(() => bytesOf(minimalRom('\n  JMP missing\n')),
    error => error instanceof AssemblyError && /undefined symbol/.test(error.message));
  assert.throws(() => bytesOf(minimalRom('\n  MOV R1, 0x100000000\n')),
    error => error instanceof AssemblyError && /immediate out of range/.test(error.message));
});

test('rejects duplicate labels and unaligned sections', () => {
  assert.throws(() => bytesOf(minimalRom('\ndup:\ndup:\n')),
    error => error instanceof AssemblyError && /duplicate label/.test(error.message));
  assert.throws(() => bytesOf(`
.section .bios
.org 0x1001
start: NOP
.section .boot
.org 0x80000000
boot: NOP
.section .kernel
.org 0x80001000
kmain: NOP
`), error => error instanceof AssemblyError && /4 KiB aligned/.test(error.message));
});

test('requires the BIOS at its aligned ROM offset', () => {
  assert.throws(() => bytesOf(`
.section .bios
.org 0x00000000
start: NOP
.section .boot
.org 0x80000000
boot: NOP
.section .kernel
.org 0x80001000
kmain: NOP
`), error => error instanceof AssemblyError && /BIOS must load at or above/.test(error.message));
});

test('rejects W+X sections', () => {
  assert.throws(() => bytesOf(`
.section .bios
.org 0x1000
.flags R|W|X
start: NOP
.section .boot
.org 0x80000000
boot: NOP
.section .kernel
.org 0x80001000
kmain: NOP
`), error => error instanceof AssemblyError && /writable and executable/.test(error.message));
});

test('encodes CSR read, write and read-modify-write forms', () => {
  const csrr = encode('CSRR', [reg(1),
    { kind: 'expr', expr: { kind: 'number', value: 0x100 } }],
  0, () => 0x100).bytes;
  assert.deepStrictEqual([...csrr], [0xd9, 0x04, 0x10, 0x00, 0x01, 0x00, 0x00]);

  const csrw = encode('CSRW', [{ kind: 'expr', expr: { kind: 'number', value: 0x100 } }, reg(4)],
    0, () => 0x100).bytes;
  assert.deepStrictEqual([...csrw], [0xdd, 0x04, 0x04, 0x00, 0x01, 0x00, 0x00]);

  const csrrw = encode('CSRRW', [reg(1),
    { kind: 'expr', expr: { kind: 'number', value: 0x100 } }, reg(2)],
  0, () => 0x100).bytes;
  assert.deepStrictEqual([...csrrw], [0xcd, 0x04, 0x12, 0x00, 0x01, 0x00, 0x00]);

  const csrrs = encode('CSRRS', [reg(3),
    { kind: 'expr', expr: { kind: 'number', value: 0x101 } }, reg(4)],
  0, () => 0x101).bytes;
  assert.deepStrictEqual([...csrrs], [0xd1, 0x04, 0x34, 0x01, 0x01, 0x00, 0x00]);

  const csrrc = encode('CSRRC', [reg(5),
    { kind: 'expr', expr: { kind: 'number', value: 0x109 } }, reg(6)],
  0, () => 0x109).bytes;
  assert.deepStrictEqual([...csrrc], [0xd5, 0x04, 0x56, 0x09, 0x01, 0x00, 0x00]);

  assert.throws(
    () => encode('CSRR', [reg(1), { kind: 'expr', expr: { kind: 'number', value: 0x999 } }]),
    error => /defined v3 CSR/.test(error.message));
  assert.throws(
    () => encode('CSRR', [reg(1), {
      kind: 'expr',
      expr: {
        kind: 'binary',
        operator: '+',
        left: { kind: 'number', value: 0x100 },
        right: { kind: 'number', value: 0 },
      },
    }]),
    error => /defined v3 CSR/.test(error.message));
});

test('encodes every AMO form and the LEA/PUSH/POP group', () => {
  const amo = {
    'AMOADD.W': 0xf3,
    'AMOSWAP.W': 0xf7,
    'AMOAND.W': 0xfb,
    'AMOOR.W': 0xff,
  };
  for (const [mnemonic, opcode] of Object.entries(amo)) {
    const { bytes } = encode(mnemonic, [reg(1), reg(3), mem(2, null)]);
    assert.deepStrictEqual([...bytes], [opcode, 0x24, 0x13, 0, 0, 0, 0], mnemonic);
  }

  const lea = encode('LEA', [reg(1), mem(2, null, { kind: 'number', value: 4 })]).bytes;
  assert.deepStrictEqual([...lea], [0x06, 0x24, 0x10, 0x04, 0x00, 0x00, 0x00]);

  assert.deepStrictEqual([...encode('PUSH', [reg(1)]).bytes], [0x08, 0x04, 0x01]);
  assert.deepStrictEqual([...encode('POP', [reg(1)]).bytes], [0x0c, 0x04, 0x10]);
  assert.deepStrictEqual([...encode('RET', []).bytes], [0x8c, 0x04, 0x00]);
  assert.deepStrictEqual([...encode('HALT', []).bytes], [0x98, 0x04, 0x00]);
  assert.deepStrictEqual([...encode('SFENCE.VMA', []).bytes], [0xe0, 0x04, 0x00]);
  assert.deepStrictEqual([...encode('RDCYCLE', [reg(1), reg(2)]).bytes], [0xc4, 0x04, 0x12]);
});

test('encodes the generic JCC form alongside its aliases', () => {
  const generic = encode('JCC', [
    { kind: 'expr', expr: { kind: 'symbol', name: 'GE' } },
    { kind: 'expr', expr: { kind: 'symbol', name: 'target' } },
  ], 0x1000, () => 0x1010).bytes;
  assert.deepStrictEqual([...generic], [0x85, 0x04, 0x0a, 0x09, 0x00, 0x00, 0x00]);

  const one = encode('JCC', [{ kind: 'expr', expr: { kind: 'symbol', name: 'target' } }],
    0x1000, () => 0x1010).bytes;
  assert.deepStrictEqual([...one], [0x85, 0x04, 0x03, 0x09, 0x00, 0x00, 0x00]);

  const alias = encode('JGE', [{ kind: 'expr', expr: { kind: 'symbol', name: 'target' } }],
    0x1000, () => 0x1010).bytes;
  assert.deepStrictEqual([...alias], [0x85, 0x04, 0x0a, 0x09, 0x00, 0x00, 0x00]);

  assert.throws(
    () => encode('JCC', [
      { kind: 'expr', expr: { kind: 'symbol', name: 'BOGUS' } },
      { kind: 'expr', expr: { kind: 'symbol', name: 'target' } },
    ]),
    error => /JCC condition/.test(error.message));
});

test('applies CST parsing for CSR names and expressions', () => {
  const source = minimalRom(`
  CSRR R1, STATUS
  CSRW FLAGS, R2
  MOV R3, 2 + 3 * 4
`);
  const result = bytesOf(source);
  const kernel = Buffer.from(result.segments.find(s => s.type === 3).bytes);
  assert.deepStrictEqual([...kernel.subarray(3, 10)], [0xd9, 0x04, 0x10, 0x00, 0x01, 0x00, 0x00]);
  assert.deepStrictEqual([...kernel.subarray(10, 17)], [0xdd, 0x04, 0x02, 0x09, 0x01, 0x00, 0x00]);
  assert.deepStrictEqual([...kernel.subarray(20, 24)], [14, 0, 0, 0]);
});

test('emits flags from .flags and rejects invalid per-type sections', () => {
  const source = minimalRom(`
.section .data
.org 0x80002000
.flags R
payload:
  .byte 1
`);
  const data = bytesOf(source).segments.find(s => s.type === 5);
  assert.strictEqual(data.flags, 1);
  assert.strictEqual(data.entryPoint, 0);

  assert.throws(() => bytesOf(`
.section .bios
.org 0x1000
.flags R|W
start: NOP
.section .boot
.org 0x80000000
boot: NOP
.section .kernel
.org 0x80001000
kmain: NOP
`), error => error instanceof AssemblyError && /BIOS must be R\/X/.test(error.message));

  assert.throws(() => bytesOf(`
.section .bios
.org 0x1000
start: NOP
.section .boot
.org 0x1000
boot: NOP
.section .kernel
.org 0x80001000
kmain: NOP
`), error => error instanceof AssemblyError && /BOOT must be in kernel space/.test(error.message));

  assert.throws(() => bytesOf(`
.section .bios
.org 0x1000
start: NOP
.section .boot
.org 0x80000000
boot: NOP
.section .kernel
.org 0x80001000
kmain: NOP
.section .data
.org 0x80002000
.flags R|X
payload: .byte 1
`), error => error instanceof AssemblyError && /DATA must be/.test(error.message));
});

test('rejects invalid directives and section placement', () => {
  assert.throws(() => bytesOf(minimalRom('\n.capability "x"\n')),
    error => error instanceof AssemblyError && /only valid in a program/.test(error.message));
  assert.throws(() => bytesOf(minimalRom('\n.org 0x1000\n')),
    error => error instanceof AssemblyError && /before section content/.test(error.message));
  assert.throws(() => bytesOf(minimalRom('\n.section .bogus\n')),
    error => error instanceof AssemblyError && /unknown section type/.test(error.message));
  assert.throws(() => bytesOf(minimalRom('\n.section .kernel\n.org 0x80002000\nx: NOP\n')),
    error => error instanceof AssemblyError && /duplicate section/.test(error.message));
  assert.throws(() => bytesOf('\nNOP\n'),
    error => error instanceof AssemblyError && /outside a section/.test(error.message));
  assert.throws(() => bytesOf(minimalRom('\n  HALT R1\n')),
    error => error instanceof AssemblyError && /takes no operands/.test(error.message));
  assert.throws(() => bytesOf(minimalRom('\n.align 3\n')),
    error => error instanceof AssemblyError && /power of two/.test(error.message));
  assert.throws(() => bytesOf(minimalRom('\n.space -1\n')),
    error => error instanceof AssemblyError && /cannot be negative/.test(error.message));
  assert.throws(() => bytesOf(minimalRom('\n.word 0x100000000\n')),
    error => error instanceof AssemblyError && /out of range/.test(error.message));
  assert.throws(() => bytesOf(minimalRom(
    '\n.section .data\n.org 0x100000000\nx: .byte 1\n')),
  error => error instanceof AssemblyError && /32-bit unsigned address/.test(error.message));
});

test('applies .space, .align padding and UTF-8 strings', () => {
  const source = `
.section .bios
.org 0x1000
.entry start
start:
  .byte 0xaa
  .align 4
  .byte 0xbb
  .space 2
  .string "ñ"
  .byte 0xcc
.section .boot
.org 0x80000000
boot: NOP
.section .kernel
.org 0x80001000
kmain: NOP
`;
  const bios = Buffer.from(bytesOf(source).segments.find(s => s.type === 1).bytes);
  assert.deepStrictEqual([...bios], [
    0xaa, 0x00, 0x00, 0x00,
    0xbb, 0x00, 0x00,
    0xc3, 0xb1, 0x00,
    0xcc,
  ]);
});

test('validates capability names against the ROM contract', () => {
  const program = capability => `
.section .program.main
.org 0x02000000
.entry main
.capability "${capability}"
main: NOP
`;
  const result = bytesOf(minimalRom(program('sync.discord.channel.create')));
  const parsed = result.segments.find(s => s.type === 4);
  assert.ok(parsed);
  assert.strictEqual(Buffer.from(parsed.bytes).subarray(0, 4).toString('ascii'), 'PRG3');

  assert.throws(() => bytesOf(minimalRom(program('bogus'))),
    error => error instanceof AssemblyError && /unknown capability/.test(error.message));
  assert.throws(() => bytesOf(minimalRom(program('module.textformatter'))),
    error => error instanceof AssemblyError && /unknown capability/.test(error.message));
});

test('reuses the assembler instance and honors .reset', () => {
  const assembler = new AssemblerV3();
  const first = assembler.assemble(minimalRom(), 'first.flowasm');
  const second = assembler.assemble(minimalRom(), 'second.flowasm');
  assert.deepStrictEqual([...first.bytes], [...second.bytes]);

  const reset = assembler.assemble(`
.section .bios
.org 0x1000
.entry start
start: NOP
alt: NOP
.section .boot
.org 0x80000000
boot: NOP
.section .kernel
.org 0x80001000
kmain: NOP
.reset alt
`, 'reset.flowasm');
  assert.strictEqual(reset.resetVector, 0x1003);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);

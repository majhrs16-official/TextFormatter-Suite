/** Instruction classifier and encoder for the v3 ISA. */

import type { Expr, InstructionStatement, Operand } from './ast';
import {
  CONDITIONS,
  CSRS,
  FORM_LENGTH,
  InstructionForm,
  JCC_ALIASES,
  OperandSize,
  findOpcode,
  type OpcodeDef,
} from './types';

export interface InstructionPlan {
  readonly statement: InstructionStatement;
  readonly opcode: OpcodeDef;
  readonly form: InstructionForm;
  readonly rd: number;
  readonly rs: number;
  readonly base: number;
  readonly indexed: boolean;
  readonly size: OperandSize;
  readonly immediate: Expr;
  readonly relative: boolean;
}

const ALU = new Set([
  'ADD', 'ADC', 'SUB', 'AND', 'OR', 'XOR', 'SHL', 'SHR', 'SAR',
  'MUL', 'DIV', 'DIVU', 'MOD', 'MODU', 'CMP', 'TEST',
]);

const CSR_OPS = new Set(['CSRRW', 'CSRRS', 'CSRRC', 'CSRR', 'CSRW']);
const ATOMIC_STORE = new Set(['SC.W', 'AMOADD.W', 'AMOSWAP.W', 'AMOAND.W', 'AMOOR.W']);
const NO_OPERAND = new Set([
  'RET', 'ECALL', 'ERET', 'HALT', 'BREAK', 'NOP', 'HOST', 'SFENCE.VMA', 'FENCE',
]);

export function classifyInstruction(
  statement: InstructionStatement,
  fail: (message: string, line: number, column: number) => void,
): InstructionPlan | undefined {
  const original = statement.mnemonic.toUpperCase();
  const operands = statement.operands;

  if (original === 'LI') {
    return immediatePlan(statement, 'MOV', operands, OperandSize.WORD, fail);
  }

  // `JCC <cond>, target` is the generic form. With one operand, `JCC` is the
  // alias for condition 3 (carry clear); two operands must not be shadowed.
  if (original === 'JCC' && operands.length === 2) {
    return jccPlan(statement, operands, fail);
  }

  if (JCC_ALIASES[original] !== undefined) {
    const condition = JCC_ALIASES[original];
    if (operands.length !== 1) {
      return invalid(statement, `${original} requires one target`, fail);
    }
    return buildPlan(statement, findOpcode('JCC')!, InstructionForm.IMMEDIATE,
      0, condition, 0, false, OperandSize.WORD,
      operandExpr(operands[0], statement, fail), true);
  }

  let mnemonic = original;
  let size = OperandSize.WORD;
  if (!findOpcode(mnemonic)) {
    const match = /^(.*)\.(B|H|W)$/.exec(original);
    if (match) {
      mnemonic = match[1];
      size = match[2] === 'B'
        ? OperandSize.BYTE
        : match[2] === 'H'
          ? OperandSize.HALF
          : OperandSize.WORD;
    }
  }

  const opcode = findOpcode(mnemonic);
  if (!opcode) {
    return invalid(statement, `unknown mnemonic '${statement.mnemonic}'`, fail);
  }
  if (size !== OperandSize.WORD && opcode.mnemonic !== 'MOV') {
    return invalid(statement, `${opcode.mnemonic} does not accept a size suffix`, fail);
  }

  if (NO_OPERAND.has(opcode.mnemonic)) {
    if (operands.length !== 0) {
      return invalid(statement, `${opcode.mnemonic} takes no operands`, fail);
    }
    return buildPlan(statement, opcode, InstructionForm.REGISTER,
      0, 0, 0, false, OperandSize.WORD, zero(), false);
  }

  if (opcode.mnemonic === 'RDCYCLE' || opcode.mnemonic === 'RDTIME') {
    if (operands.length !== 2 || operands[0].kind !== 'reg' || operands[1].kind !== 'reg') {
      return invalid(statement, `${opcode.mnemonic} requires two registers`, fail);
    }
    if (operands[0].number === operands[1].number) {
      return invalid(statement, `${opcode.mnemonic} requires different registers`, fail);
    }
    return buildPlan(statement, opcode, InstructionForm.REGISTER,
      operands[0].number, operands[1].number, 0, false,
      OperandSize.WORD, zero(), false);
  }

  if (CSR_OPS.has(opcode.mnemonic)) {
    return csrPlan(statement, opcode, operands, fail);
  }

  if (opcode.mnemonic === 'PUSH' || opcode.mnemonic === 'POP') {
    if (operands.length !== 1 || operands[0].kind !== 'reg') {
      return invalid(statement, `${opcode.mnemonic} requires one register`, fail);
    }
    return buildPlan(statement, opcode, InstructionForm.REGISTER,
      opcode.mnemonic === 'POP' ? operands[0].number : 0,
      opcode.mnemonic === 'PUSH' ? operands[0].number : 0,
      0, false, OperandSize.WORD, zero(), false);
  }

  if (opcode.mnemonic === 'LR.W') {
    if (operands.length !== 2 || operands[0].kind !== 'reg' || operands[1].kind !== 'mem') {
      return invalid(statement, 'LR.W requires Rd, [base + index + disp]', fail);
    }
    const mem = operands[1];
    return buildPlan(statement, opcode, InstructionForm.LOAD,
      operands[0].number, mem.index ?? 0, mem.base, mem.index !== null,
      OperandSize.WORD, mem.displacement, false);
  }

  if (ATOMIC_STORE.has(opcode.mnemonic)) {
    if (
      operands.length !== 3 ||
      operands[0].kind !== 'reg' ||
      operands[1].kind !== 'reg' ||
      operands[2].kind !== 'mem'
    ) {
      return invalid(statement,
        `${opcode.mnemonic} requires Rd, Rs, [base + disp]`, fail);
    }
    const mem = operands[2];
    if (mem.index !== null) {
      return invalid(statement, `${opcode.mnemonic} does not accept an index register`, fail);
    }
    return buildPlan(statement, opcode, InstructionForm.STORE,
      operands[0].number, operands[1].number, mem.base, false,
      OperandSize.WORD, mem.displacement, false);
  }

  if (opcode.mnemonic === 'LEA') {
    if (size !== OperandSize.WORD) {
      return invalid(statement, 'LEA ignores size and does not accept a size suffix', fail);
    }
    if (operands.length !== 2 || operands[0].kind !== 'reg' || operands[1].kind !== 'mem') {
      return invalid(statement, 'LEA requires Rd, [base + index + disp]', fail);
    }
    const mem = operands[1];
    return buildPlan(statement, opcode, InstructionForm.LOAD,
      operands[0].number, mem.index ?? 0, mem.base, mem.index !== null,
      OperandSize.WORD, mem.displacement, false);
  }

  if (opcode.mnemonic === 'MOV') {
    if (operands.length !== 2) {
      return invalid(statement, 'MOV requires two operands', fail);
    }
    if (operands[0].kind === 'mem') {
      if (operands[1].kind !== 'reg') {
        return invalid(statement, 'MOV [mem], Rs requires a register source', fail);
      }
      return buildPlan(statement, opcode, InstructionForm.STORE,
        operands[1].number, operands[0].index ?? 0, operands[0].base,
        operands[0].index !== null, size, operands[0].displacement, false);
    }
    if (operands[0].kind !== 'reg') {
      return invalid(statement, 'MOV destination must be a register or memory', fail);
    }
    if (operands[1].kind === 'mem') {
      return buildPlan(statement, opcode, InstructionForm.LOAD,
        operands[0].number, operands[1].index ?? 0, operands[1].base,
        operands[1].index !== null, size, operands[1].displacement, false);
    }
    if (operands[1].kind === 'reg') {
      return buildPlan(statement, opcode, InstructionForm.REGISTER,
        operands[0].number, operands[1].number, 0, false, size, zero(), false);
    }
    if (size !== OperandSize.WORD) {
      return invalid(statement, 'MOV immediate requires 32-bit size', fail);
    }
    return buildPlan(statement, opcode, InstructionForm.IMMEDIATE,
      operands[0].number, 0, 0, false, OperandSize.WORD,
      operands[1].expr, false);
  }

  if (ALU.has(opcode.mnemonic)) {
    if (size !== OperandSize.WORD) {
      return invalid(statement, `${opcode.mnemonic} requires 32-bit size`, fail);
    }
    if (operands.length !== 2 || operands[0].kind !== 'reg') {
      return invalid(statement, `${opcode.mnemonic} requires Rd, operand`, fail);
    }
    if (operands[1].kind === 'reg') {
      return buildPlan(statement, opcode, InstructionForm.REGISTER,
        operands[0].number, operands[1].number, 0, false,
        OperandSize.WORD, zero(), false);
    }
    if (operands[1].kind === 'expr') {
      return buildPlan(statement, opcode, InstructionForm.IMMEDIATE,
        operands[0].number, 0, 0, false, OperandSize.WORD,
        operands[1].expr, false);
    }
    return invalid(statement, `${opcode.mnemonic} operand must be a register or immediate`, fail);
  }

  if (opcode.mnemonic === 'JMP' || opcode.mnemonic === 'CALL') {
    if (operands.length !== 1) {
      return invalid(statement, `${opcode.mnemonic} requires one target`, fail);
    }
    if (operands[0].kind === 'reg') {
      return buildPlan(statement, opcode, InstructionForm.REGISTER,
        0, operands[0].number, 0, false, OperandSize.WORD, zero(), false);
    }
    return buildPlan(statement, opcode, InstructionForm.IMMEDIATE,
      0, 0, 0, false, OperandSize.WORD,
      operandExpr(operands[0], statement, fail), true);
  }

  return invalid(statement, `unsupported instruction '${opcode.mnemonic}'`, fail);
}

function csrPlan(
  statement: InstructionStatement,
  opcode: OpcodeDef,
  operands: readonly Operand[],
  fail: (message: string, line: number, column: number) => void,
): InstructionPlan | undefined {
  let rd = 0;
  let rs = 0;
  let csr: Expr;

  if (opcode.mnemonic === 'CSRR') {
    if (operands.length !== 2 || operands[0].kind !== 'reg') {
      return invalid(statement, 'CSRR requires Rd, csr', fail);
    }
    rd = operands[0].number;
    csr = operandExpr(operands[1], statement, fail);
  } else if (opcode.mnemonic === 'CSRW') {
    if (operands.length !== 2 || operands[1].kind !== 'reg') {
      return invalid(statement, 'CSRW requires csr, Rs', fail);
    }
    rs = operands[1].number;
    csr = operandExpr(operands[0], statement, fail);
  } else {
    if (
      operands.length !== 3 ||
      operands[0].kind !== 'reg' ||
      operands[2].kind !== 'reg'
    ) {
      return invalid(statement, `${opcode.mnemonic} requires Rd, csr, Rs`, fail);
    }
    rd = operands[0].number;
    rs = operands[2].number;
    csr = operandExpr(operands[1], statement, fail);
  }

  if (csr.kind !== 'number' || !CSR_VALUES.has(csr.value)) {
    return invalid(statement, 'CSR must be a defined v3 CSR address', fail);
  }

  return buildPlan(statement, opcode, InstructionForm.IMMEDIATE,
    rd, rs, 0, false, OperandSize.WORD, csr, false);
}

const CSR_VALUES = new Set(Object.values(CSRS));

function jccPlan(
  statement: InstructionStatement,
  operands: readonly Operand[],
  fail: (message: string, line: number, column: number) => void,
): InstructionPlan | undefined {
  if (operands.length !== 2) {
    return invalid(statement, 'JCC requires condition, target', fail);
  }
  const conditionExpr = operandExpr(operands[0], statement, fail);
  let condition: number | undefined;
  if (conditionExpr.kind === 'symbol') {
    condition = CONDITIONS[conditionExpr.name.toUpperCase()];
  } else if (conditionExpr.kind === 'number') {
    condition = conditionExpr.value;
  }
  if (condition === undefined || condition < 0 || condition > 15) {
    return invalid(statement, 'JCC condition must be a known condition or 0..15', fail);
  }
  return buildPlan(statement, findOpcode('JCC')!, InstructionForm.IMMEDIATE,
    0, condition, 0, false, OperandSize.WORD,
    operandExpr(operands[1], statement, fail), true);
}

function immediatePlan(
  statement: InstructionStatement,
  mnemonic: string,
  operands: readonly Operand[],
  size: OperandSize,
  fail: (message: string, line: number, column: number) => void,
): InstructionPlan | undefined {
  if (operands.length !== 2 || operands[0].kind !== 'reg') {
    return invalid(statement, `${mnemonic} requires Rd, immediate`, fail);
  }
  return buildPlan(statement, findOpcode(mnemonic)!, InstructionForm.IMMEDIATE,
    operands[0].number, 0, 0, false, size,
    operandExpr(operands[1], statement, fail), false);
}

function buildPlan(
  statement: InstructionStatement,
  opcode: OpcodeDef,
  form: InstructionForm,
  rd: number,
  rs: number,
  base: number,
  indexed: boolean,
  size: OperandSize,
  immediate: Expr,
  relative: boolean,
): InstructionPlan | undefined {
  if (!opcode.forms.includes(form)) {
    return undefined;
  }
  return { statement, opcode, form, rd, rs, base, indexed, size, immediate, relative };
}

function operandExpr(
  operand: Operand,
  statement: InstructionStatement,
  fail: (message: string, line: number, column: number) => void,
): Expr {
  if (operand.kind === 'expr') {
    return operand.expr;
  }
  if (operand.kind === 'reg') {
    fail(`expected an expression, found R${operand.number}`,
      statement.line, statement.column);
    return zero();
  }
  fail('expected a register or immediate expression, found memory operand',
    statement.line, statement.column);
  return zero();
}

function invalid(
  statement: InstructionStatement,
  message: string,
  fail: (message: string, line: number, column: number) => void,
): undefined {
  fail(message, statement.line, statement.column);
  return undefined;
}

function zero(): Expr {
  return { kind: 'number', value: 0 };
}

export function instructionLength(plan: InstructionPlan): number {
  return FORM_LENGTH[plan.form];
}

export function encodeInstruction(
  plan: InstructionPlan,
  address: number,
  resolve: (expr: Expr) => number,
): Uint8Array {
  const value = resolve(plan.immediate);
  const relative = plan.relative ? value - (address + FORM_LENGTH[plan.form]) : value;
  const opcodeByte =
    (plan.opcode.family << 6) |
    (plan.opcode.operation << 2) |
    plan.form;
  const descriptor =
    (plan.rd << 12) |
    (plan.rs << 8) |
    (plan.base << 4) |
    (plan.indexed ? 0x08 : 0) |
    (plan.size << 1);
  const immediate = plan.form === InstructionForm.REGISTER ? 0 : relative;

  const bytes = new Uint8Array(FORM_LENGTH[plan.form]);
  bytes[0] = opcodeByte & 0xff;
  bytes[1] = descriptor & 0xff;
  bytes[2] = (descriptor >>> 8) & 0xff;
  if (plan.form !== InstructionForm.REGISTER) {
    bytes[3] = immediate & 0xff;
    bytes[4] = (immediate >>> 8) & 0xff;
    bytes[5] = (immediate >>> 16) & 0xff;
    bytes[6] = (immediate >>> 24) & 0xff;
  }
  return bytes;
}

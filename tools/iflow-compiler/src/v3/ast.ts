/** Syntax tree of the v3 assembler. */

export type Expr =
  | { readonly kind: 'number'; readonly value: number }
  | { readonly kind: 'symbol'; readonly name: string }
  | { readonly kind: 'unary'; readonly operator: '+' | '-'; readonly operand: Expr }
  | {
      readonly kind: 'binary';
      readonly operator: '+' | '-' | '*' | '/' | '%';
      readonly left: Expr;
      readonly right: Expr;
    };

export interface RegisterOperand {
  readonly kind: 'reg';
  readonly number: number;
}

export interface ExprOperand {
  readonly kind: 'expr';
  readonly expr: Expr;
}

export interface MemoryOperand {
  readonly kind: 'mem';
  readonly base: number;
  readonly index: number | null;
  readonly displacement: Expr;
}

export type Operand = RegisterOperand | ExprOperand | MemoryOperand;

export interface SourceLocation {
  readonly line: number;
  readonly column: number;
}

export interface InstructionStatement extends SourceLocation {
  readonly kind: 'instruction';
  readonly mnemonic: string;
  readonly operands: readonly Operand[];
}

export type DirectiveName =
  | 'section'
  | 'org'
  | 'entry'
  | 'reset'
  | 'align'
  | 'byte'
  | 'half'
  | 'word'
  | 'string'
  | 'space'
  | 'capability'
  | 'flags'
  | 'globl'
  | 'data'
  | 'bss';

export interface DirectiveStatement extends SourceLocation {
  readonly kind: 'directive';
  readonly name: DirectiveName;
  readonly arguments: readonly Expr[];
  readonly textArguments: readonly string[];
}

export interface LabelStatement extends SourceLocation {
  readonly kind: 'label';
  readonly name: string;
}

export type Statement = InstructionStatement | DirectiveStatement | LabelStatement;

export interface SourceFile {
  readonly path: string;
  readonly statements: readonly Statement[];
}

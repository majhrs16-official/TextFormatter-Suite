/** Recursive-descent parser for `.flowasm` v3. */

import type {
  DirectiveName,
  DirectiveStatement,
  Expr,
  InstructionStatement,
  LabelStatement,
  MemoryOperand,
  Operand,
  SourceFile,
  Statement,
} from './ast';
import { Lexer, type Token } from './lexer';
import { CSRS, findOpcode } from './types';
import type { Diagnostic } from './types';

const DIRECTIVES = new Set<DirectiveName>([
  'section',
  'org',
  'entry',
  'reset',
  'align',
  'byte',
  'half',
  'word',
  'string',
  'space',
  'capability',
  'flags',
  'globl',
]);

const REGISTER_ALIASES: Readonly<Record<string, number>> = Object.freeze({
  SP: 14,
  FP: 13,
  LR: 15,
});

export class Parser {
  private index = 0;

  constructor(
    private readonly tokens: readonly Token[],
    private readonly file: string,
    private readonly diagnostics: Diagnostic[],
  ) {}

  parse(): SourceFile {
    const statements: Statement[] = [];
    while (!this.at('eof')) {
      if (this.match('newline')) {
        continue;
      }
      const before = this.index;
      this.parseLine(statements);
      if (this.index === before) {
        this.error(this.current(), 'unexpected token');
        this.skipLine();
      }
    }
    return { path: this.file, statements };
  }

  private parseLine(statements: Statement[]): void {
    let sawLabel = false;
    while (this.at('ident') && this.peek(1).kind === 'punct' && this.peek(1).text === ':') {
      const token = this.advance();
      this.advance();
      const label: LabelStatement = {
        kind: 'label',
        name: token.text,
        line: token.line,
        column: token.column,
      };
      statements.push(label);
      sawLabel = true;
      if (this.at('newline') || this.at('eof')) {
        return;
      }
    }

    if (this.at('newline') || this.at('eof')) {
      if (!sawLabel) {
        this.error(this.current(), 'empty statement');
      }
      return;
    }

    const token = this.current();
    if (token.kind !== 'ident') {
      this.error(token, 'expected a directive, instruction or label');
      this.skipLine();
      return;
    }

    if (token.text.startsWith('.')) {
      statements.push(this.parseDirective());
      return;
    }

    statements.push(this.parseInstruction());
  }

  private parseDirective(): DirectiveStatement {
    const token = this.advance();
    const rawName = token.text.slice(1).toLowerCase();
    if (!DIRECTIVES.has(rawName as DirectiveName)) {
      this.error(token, `unknown directive '${token.text}'`);
      this.skipLine();
      return {
        kind: 'directive',
        name: 'globl',
        arguments: [],
        textArguments: [],
        line: token.line,
        column: token.column,
      };
    }
    const name = rawName as DirectiveName;
    const args: Expr[] = [];
    const textArguments: string[] = [];

    switch (name) {
      case 'section':
      case 'entry':
      case 'reset':
      case 'globl': {
        const value = this.expectIdent(`directive .${name} requires a name`);
        if (value) {
          textArguments.push(value.text.replace(/^\./, ''));
        }
        break;
      }
      case 'org':
      case 'align':
      case 'space': {
        if (!this.at('newline') && !this.at('eof')) {
          args.push(this.parseExpression());
        } else {
          this.error(token, `directive .${name} requires an expression`);
        }
        break;
      }
      case 'byte':
      case 'half':
      case 'word': {
        while (!this.at('newline') && !this.at('eof')) {
          args.push(this.parseExpression());
          if (!this.matchPunct(',')) {
            break;
          }
        }
        break;
      }
      case 'string':
      case 'capability': {
        while (!this.at('newline') && !this.at('eof')) {
          const value = this.current();
          if (value.kind === 'string') {
            this.advance();
            textArguments.push(value.text);
          } else if (value.kind === 'ident') {
            this.advance();
            textArguments.push(value.text);
          } else {
            this.error(value, `directive .${name} requires a string`);
            this.advance();
          }
          if (!this.matchPunct(',')) {
            break;
          }
        }
        break;
      }
      case 'flags': {
        while (!this.at('newline') && !this.at('eof')) {
          if (this.current().kind === 'ident') {
            textArguments.push(this.advance().text.toUpperCase());
          } else if (this.current().text === '|' || this.current().text === ',') {
            this.advance();
          } else {
            this.error(this.current(), 'directive .flags expects R, W, X or U');
            this.advance();
          }
        }
        break;
      }
    }

    if (!this.at('newline') && !this.at('eof')) {
      this.error(this.current(), 'unexpected token after directive');
      this.skipLine();
    }
    this.match('newline');
    return {
      kind: 'directive',
      name,
      arguments: args,
      textArguments,
      line: token.line,
      column: token.column,
    };
  }

  private parseInstruction(): InstructionStatement {
    const token = this.advance();
    const operands: Operand[] = [];
    while (!this.at('newline') && !this.at('eof')) {
      operands.push(this.parseOperand());
      if (!this.matchPunct(',')) {
        break;
      }
    }
    if (!this.at('newline') && !this.at('eof')) {
      this.error(this.current(), 'unexpected token after operand');
      this.skipLine();
    }
    this.match('newline');
    return {
      kind: 'instruction',
      mnemonic: token.text,
      operands,
      line: token.line,
      column: token.column,
    };
  }

  private parseOperand(): Operand {
    const token = this.current();
    if (token.kind === 'punct' && token.text === '[') {
      return this.parseMemory();
    }
    if (token.kind === 'ident') {
      const register = registerNumber(token.text);
      if (register !== undefined) {
        this.advance();
        return { kind: 'reg', number: register };
      }
    }
    return { kind: 'expr', expr: this.parseExpression() };
  }

  private parseMemory(): MemoryOperand {
    const open = this.advance();
    const baseToken = this.current();
    const base = baseToken.kind === 'ident' ? registerNumber(baseToken.text) : undefined;
    if (base === undefined) {
      this.error(open, 'memory operand requires a base register');
      this.skipTo(']');
      return { kind: 'mem', base: 0, index: null, displacement: numberExpr(0) };
    }
    this.advance();

    let index: number | null = null;
    let displacement: Expr = numberExpr(0);
    if (this.current().text === '+' || this.current().text === '-') {
      const sign = this.advance().text;
      if (this.current().kind === 'ident') {
        const possibleIndex = registerNumber(this.current().text);
        if (possibleIndex !== undefined) {
          index = possibleIndex;
          this.advance();
          if (this.current().text === '+' || this.current().text === '-') {
            const dispSign = this.advance().text;
            const value = this.parseExpression();
            displacement = dispSign === '-' ? unary('-', value) : value;
          }
        } else {
          const value = this.parseExpression();
          displacement = sign === '-' ? unary('-', value) : value;
        }
      } else {
        const value = this.parseExpression();
        displacement = sign === '-' ? unary('-', value) : value;
      }
    }

    if (!this.matchPunct(']')) {
      this.error(this.current(), "expected ']'");
      this.skipTo(']');
      this.matchPunct(']');
    }
    return { kind: 'mem', base, index, displacement };
  }

  private parseExpression(): Expr {
    let left = this.parseTerm();
    while (this.current().text === '+' || this.current().text === '-') {
      const operator = this.advance().text as '+' | '-';
      left = { kind: 'binary', operator, left, right: this.parseTerm() };
    }
    return left;
  }

  private parseTerm(): Expr {
    let left = this.parseUnary();
    while (
      this.current().text === '*' ||
      this.current().text === '/' ||
      this.current().text === '%'
    ) {
      const operator = this.advance().text as '*' | '/' | '%';
      left = { kind: 'binary', operator, left, right: this.parseUnary() };
    }
    return left;
  }

  private parseUnary(): Expr {
    if (this.current().text === '+' || this.current().text === '-') {
      const operator = this.advance().text as '+' | '-';
      return unary(operator, this.parseUnary());
    }
    return this.parsePrimary();
  }

  private parsePrimary(): Expr {
    const token = this.current();
    if (token.kind === 'number') {
      this.advance();
      return numberExpr(token.value ?? 0);
    }
    if (token.kind === 'ident') {
      this.advance();
      const csr = CSRS[token.text.toUpperCase()];
      if (csr !== undefined) {
        return numberExpr(csr);
      }
      return { kind: 'symbol', name: token.text };
    }
    if (token.kind === 'punct' && token.text === '(') {
      this.advance();
      const value = this.parseExpression();
      if (!this.matchPunct(')')) {
        this.error(this.current(), "expected ')'");
      }
      return value;
    }
    this.error(token, 'expected an expression');
    this.advance();
    return numberExpr(0);
  }

  private expectIdent(message: string): Token | undefined {
    if (this.current().kind === 'ident') {
      return this.advance();
    }
    this.error(this.current(), message);
    return undefined;
  }

  private at(kind: Token['kind']): boolean {
    return this.current().kind === kind;
  }

  private match(kind: Token['kind']): boolean {
    if (this.at(kind)) {
      this.advance();
      return true;
    }
    return false;
  }

  private matchPunct(text: string): boolean {
    if (this.current().kind === 'punct' && this.current().text === text) {
      this.advance();
      return true;
    }
    return false;
  }

  private current(): Token {
    return this.tokens[this.index] ?? this.tokens[this.tokens.length - 1];
  }

  private peek(offset: number): Token {
    return this.tokens[this.index + offset] ?? this.current();
  }

  private advance(): Token {
    const token = this.current();
    if (this.index < this.tokens.length - 1) {
      this.index += 1;
    }
    return token;
  }

  private skipLine(): void {
    while (!this.at('newline') && !this.at('eof')) {
      this.advance();
    }
    this.match('newline');
  }

  private skipTo(text: string): void {
    while (!this.at('eof') && !(this.current().kind === 'punct' && this.current().text === text)) {
      this.advance();
    }
  }

  private error(token: Token, message: string): void {
    this.diagnostics.push({
      severity: 'error',
      message,
      line: token.line,
      column: token.column,
      file: this.file,
    });
  }
}

export function parseSource(source: string, file: string, diagnostics: Diagnostic[]): SourceFile {
  const lexer = new Lexer(source, file, diagnostics);
  return new Parser(lexer.tokenize(), file, diagnostics).parse();
}

export function registerNumber(text: string): number | undefined {
  const upper = text.toUpperCase();
  const alias = REGISTER_ALIASES[upper];
  if (alias !== undefined) {
    return alias;
  }
  const match = /^R([0-9]|1[0-5])$/.exec(upper);
  return match ? Number(match[1]) : undefined;
}

export function isKnownMnemonic(text: string): boolean {
  return findOpcode(text) !== undefined;
}

function numberExpr(value: number): Expr {
  return { kind: 'number', value };
}

function unary(operator: '+' | '-', operand: Expr): Expr {
  return { kind: 'unary', operator, operand };
}

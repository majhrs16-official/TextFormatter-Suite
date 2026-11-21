/** Lexer for `.flowasm` v3. */

import type { Diagnostic } from './types';

export type TokenKind = 'ident' | 'number' | 'string' | 'punct' | 'newline' | 'eof';

export interface Token {
  readonly kind: TokenKind;
  readonly text: string;
  readonly value?: number;
  readonly line: number;
  readonly column: number;
}

const SINGLE_CHAR = new Set([',', ':', '[', ']', '(', ')', '+', '-', '*', '/', '%', '|']);

export class Lexer {
  private index = 0;
  private line = 1;
  private column = 1;

  constructor(
    private readonly source: string,
    private readonly file: string,
    private readonly diagnostics: Diagnostic[],
  ) {}

  tokenize(): Token[] {
    const tokens: Token[] = [];
    while (!this.done()) {
      const ch = this.peek();
      if (ch === ' ' || ch === '\t' || ch === '\r') {
        this.advance();
        continue;
      }
      if (ch === '\n') {
        tokens.push(this.token('newline', '\n'));
        this.advance();
        continue;
      }
      if (ch === ';' || ch === '#') {
        this.skipLine();
        continue;
      }
      if (ch === '/' && this.peek(1) === '/') {
        this.skipLine();
        continue;
      }
      if (ch === '"') {
        tokens.push(this.readString());
        continue;
      }
      if (this.isDigit(ch)) {
        tokens.push(this.readNumber());
        continue;
      }
      if (this.isIdentStart(ch)) {
        tokens.push(this.readIdent());
        continue;
      }
      if (SINGLE_CHAR.has(ch)) {
        tokens.push(this.token('punct', ch));
        this.advance();
        continue;
      }
      this.error(`unexpected character '${ch}'`);
      this.advance();
    }
    tokens.push(this.token('eof', ''));
    return tokens;
  }

  private readIdent(): Token {
    const startLine = this.line;
    const startColumn = this.column;
    let text = '';
    while (!this.done() && this.isIdentPart(this.peek())) {
      text += this.peek();
      this.advance();
    }
    return { kind: 'ident', text, line: startLine, column: startColumn };
  }

  private readNumber(): Token {
    const startLine = this.line;
    const startColumn = this.column;
    let text = '';
    if (this.peek() === '0' && (this.peek(1) === 'x' || this.peek(1) === 'X')) {
      text += this.peek();
      this.advance();
      text += this.peek();
      this.advance();
      while (!this.done() && /[0-9a-fA-F_]/.test(this.peek())) {
        text += this.peek();
        this.advance();
      }
    } else if (this.peek() === '0' && (this.peek(1) === 'b' || this.peek(1) === 'B')) {
      text += this.peek();
      this.advance();
      text += this.peek();
      this.advance();
      while (!this.done() && /[01_]/.test(this.peek())) {
        text += this.peek();
        this.advance();
      }
    } else {
      while (!this.done() && /[0-9_]/.test(this.peek())) {
        text += this.peek();
        this.advance();
      }
    }
    const normalized = text.replace(/_/g, '');
    const value = Number(normalized);
    if (!Number.isSafeInteger(value)) {
      this.errorAt(startLine, startColumn, `integer out of range: ${text}`);
    }
    return {
      kind: 'number',
      text,
      value: Number.isSafeInteger(value) ? value : 0,
      line: startLine,
      column: startColumn,
    };
  }

  private readString(): Token {
    const startLine = this.line;
    const startColumn = this.column;
    this.advance();
    let text = '';
    let closed = false;
    while (!this.done()) {
      const ch = this.peek();
      if (ch === '"') {
        closed = true;
        this.advance();
        break;
      }
      if (ch === '\n') {
        break;
      }
      if (ch !== '\\') {
        text += ch;
        this.advance();
        continue;
      }
      this.advance();
      if (this.done()) {
        break;
      }
      const escaped = this.peek();
      this.advance();
      switch (escaped) {
        case 'n':
          text += '\n';
          break;
        case 'r':
          text += '\r';
          break;
        case 't':
          text += '\t';
          break;
        case '0':
          text += '\0';
          break;
        case '\\':
          text += '\\';
          break;
        case '"':
          text += '"';
          break;
        case 'x': {
          const hex = this.source.slice(this.index, this.index + 2);
          if (!/^[0-9a-fA-F]{2}$/.test(hex)) {
            this.errorAt(startLine, startColumn, 'invalid \\x escape');
            break;
          }
          text += String.fromCharCode(parseInt(hex, 16));
          this.advance();
          this.advance();
          break;
        }
        default:
          this.errorAt(startLine, startColumn, `invalid escape '\\${escaped}'`);
      }
    }
    if (!closed) {
      this.errorAt(startLine, startColumn, 'unterminated string literal');
    }
    return { kind: 'string', text, line: startLine, column: startColumn };
  }

  private skipLine(): void {
    while (!this.done() && this.peek() !== '\n') {
      this.advance();
    }
  }

  private token(kind: TokenKind, text: string): Token {
    return { kind, text, line: this.line, column: this.column };
  }

  private error(message: string): void {
    this.errorAt(this.line, this.column, message);
  }

  private errorAt(line: number, column: number, message: string): void {
    this.diagnostics.push({
      severity: 'error',
      message,
      line,
      column,
      file: this.file,
    });
  }

  private peek(offset = 0): string {
    return this.source[this.index + offset] ?? '';
  }

  private advance(): void {
    if (this.source[this.index] === '\n') {
      this.line += 1;
      this.column = 1;
    } else {
      this.column += 1;
    }
    this.index += 1;
  }

  private done(): boolean {
    return this.index >= this.source.length;
  }

  private isDigit(ch: string): boolean {
    return ch >= '0' && ch <= '9';
  }

  private isIdentStart(ch: string): boolean {
    return /[A-Za-z_.$]/.test(ch);
  }

  private isIdentPart(ch: string): boolean {
    return /[A-Za-z0-9_.$]/.test(ch);
  }
}

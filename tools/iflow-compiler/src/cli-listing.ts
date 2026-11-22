/** Shared symbol listing for the unified `iflow-as` CLI. */

export interface ListingSymbol {
  readonly name: string;
  readonly address: number;
}

/**
 * Writes the symbol table to stdout. `-l/--list` is identical in both
 * backends: it never creates a file, so callers redirect with `> file.lst`
 * when they want one.
 */
export function printSymbolListing(symbols: readonly ListingSymbol[]): void {
  for (const symbol of symbols) {
    console.log(`  ${symbol.name.padEnd(32)} = 0x${symbol.address.toString(16).padStart(8, '0')}`);
  }
}

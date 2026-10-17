/**
 * iFlow VM Assembler - Two-pass assembler for .flowasm to .flow binary
 */
import { AssemblerOptions, AssemblerResult, ProgramImage } from './types';
declare class Assembler {
    private options;
    private diagnostics;
    private labels;
    private instructions;
    private symbols;
    private capabilities;
    private currentAddress;
    private currentLine;
    private currentFile;
    private currentSection;
    private constantPool;
    private firmwareEnabled;
    constructor(options?: AssemblerOptions);
    assemble(source: string, fileName?: string): Promise<AssemblerResult>;
    private reset;
    private firstPass;
    private secondPass;
    /**
     * Generates the firmware ROM (4KB at 0x0000-0x0FFF).
     * Contains: vector table, service constants, string/memory utils, host wrappers.
     */
    private generateFirmware;
    /**
     * Emits a firmware ROM constant.
     * <p>
     * The constant is registered as an <em>equate</em> (name &rarr; value), not as a
     * storage address: {@code LI R0, SERVICE_PROPERTY_READ} must load {@code 0x0100},
     * not the ROM offset where the value happens to live. A program that declares the
     * same name wins, so ROM constants never collide with user {@code .const}/{@code .equ}
     * definitions.
     * </p>
     */
    private emitConst;
    /** Registers a ROM-provided equate; an existing (user) definition is kept. */
    private defineEquate;
    /**
     * Effective origin for user code. When the firmware ROM is prepended it occupies
     * 0x0000-0x0FFF, so user code starts at page 1 (0x1000) unless the caller asked
     * for a higher origin. Labels, entry point and emitted addresses all use this base
     * so they stay consistent with where the code is actually placed.
     */
    private userOrigin;
    private handleDirective;
    private defineLabel;
    private parseInstruction;
    private validateOperands;
    private getExpectedOperands;
    private tokenize;
    private getOpcode;
    private parseOperands;
    private isNumber;
    private parseNumber;
    private calculateSize;
    private emitOperands;
    private getRegOperand;
    private getImm8Operand;
    private getImm16Operand;
    private getVaddrOperand;
    private getPackedRROperand;
    private getBranchOffset;
    private createResultSync;
    private createResult;
    private serializeProgramImageNoHash;
    private computeSHA256;
    private getEntryPointAddress;
    private generateDebugInfo;
    serializeProgramImage(image: ProgramImage): Uint8Array;
    private addDiagnostic;
}
export { Assembler };
//# sourceMappingURL=assembler.d.ts.map
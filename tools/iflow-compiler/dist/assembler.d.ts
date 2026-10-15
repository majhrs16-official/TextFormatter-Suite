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
    private emitConst;
    private emitInstr;
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
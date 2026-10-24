package me.majhrs16.suite.iflow.vm.isa;

/**
 * ISA v1 Opcodes.
 * <p>
 * Encoding uses a mixed format: instruction length is determined by the opcode.
 * Ranges are reserved for instruction families.
 * </p>
 */
public final class Opcode {

    // Data transfer and memory (0x00–0x1F)
    public static final int LI      = 0x00; // RI16: Load Immediate 16-bit
    public static final int MOV     = 0x01; // RR:   Move register
    public static final int LDB     = 0x02; // MEM:  Load Byte
    public static final int LDW     = 0x03; // MEM:  Load Word
    public static final int STB     = 0x04; // MEM:  Store Byte
    public static final int STW     = 0x05; // MEM:  Store Word
    public static final int COPY    = 0x06; // BLOCK: Copy non-overlapping
    public static final int MOVE    = 0x07; // BLOCK: Move (allows overlap)

    // Indirect memory access (ISA v2, 0x08–0x0B) — address comes from a register
    public static final int LDBI    = 0x08; // RR:   Load byte  Rd, [Rs]
    public static final int LDWI    = 0x09; // RR:   Load word  Rd, [Rs]
    public static final int STBI    = 0x0A; // RR:   Store byte Rv, [Ra]
    public static final int STWI    = 0x0B; // RR:   Store word Rv, [Ra]

    // Arithmetic (0x20–0x2F)
    public static final int ADD     = 0x20; // RR:   Add
    public static final int SUB     = 0x21; // RR:   Subtract
    public static final int MUL     = 0x22; // RR:   Multiply
    public static final int DIV     = 0x23; // RR:   Divide (signed)
    public static final int MOD     = 0x24; // RR:   Modulo (signed)

    // Logic (0x28–0x2F)
    public static final int AND     = 0x28; // RR:   Bitwise AND
    public static final int OR      = 0x29; // RR:   Bitwise OR
    public static final int XOR     = 0x2A; // RR:   Bitwise XOR
    public static final int NOT     = 0x2B; // R:    Bitwise NOT

    // Shifts (0x2C–0x2F)
    public static final int SHL     = 0x2C; // RR:   Shift Left Logical
    public static final int SHR     = 0x2D; // RR:   Shift Right Logical
    public static final int SAR     = 0x2E; // RR:   Shift Right Arithmetic

    // Comparison (0x30–0x3F)
    public static final int CMP     = 0x30; // RR:   Compare (signed)
    public static final int CMPU    = 0x31; // RR:   Compare (unsigned)

    // Flow control (0x40–0x5F)
    public static final int JMP     = 0x40; // BR16: Jump relative
    public static final int JZ      = 0x41; // BR16: Jump if Zero
    public static final int JNZ     = 0x42; // BR16: Jump if Not Zero
    public static final int JC      = 0x43; // BR16: Jump if Carry
    public static final int JV      = 0x44; // BR16: Jump if Overflow
    public static final int CALL    = 0x48; // BR16: Call subroutine
    public static final int RET     = 0x49; // R:    Return (1 byte, no operand)

    // Services (0x60–0x6F)
    public static final int HOST    = 0x60; // HOST: Generic host service call

    // Control (0x68–0x6F)
    public static final int HALT    = 0x68; // R:    Halt execution (normal)
    public static final int TRAP    = 0x69; // RI8:  Trap with code

    // Reserved for extensions (0x70–0xEF)
    // Invalid in ISA v1 (0xF0–0xFF)

    private Opcode() {}

    public static boolean isValid(int opcode) {
        return instructionSize(opcode) > 0;
    }

    public static String name(int opcode) {
        return switch (opcode) {
            case LI -> "LI";
            case MOV -> "MOV";
            case LDB -> "LDB";
            case LDW -> "LDW";
            case STB -> "STB";
            case STW -> "STW";
            case COPY -> "COPY";
            case MOVE -> "MOVE";
            case LDBI -> "LDBI";
            case LDWI -> "LDWI";
            case STBI -> "STBI";
            case STWI -> "STWI";
            case ADD -> "ADD";
            case SUB -> "SUB";
            case MUL -> "MUL";
            case DIV -> "DIV";
            case MOD -> "MOD";
            case AND -> "AND";
            case OR -> "OR";
            case XOR -> "XOR";
            case NOT -> "NOT";
            case SHL -> "SHL";
            case SHR -> "SHR";
            case SAR -> "SAR";
            case CMP -> "CMP";
            case CMPU -> "CMPU";
            case JMP -> "JMP";
            case JZ -> "JZ";
            case JNZ -> "JNZ";
            case JC -> "JC";
            case JV -> "JV";
            case CALL -> "CALL";
            case RET -> "RET";
            case HOST -> "HOST";
            case HALT -> "HALT";
            case TRAP -> "TRAP";
            default -> "UNKNOWN(0x" + Integer.toHexString(opcode).toUpperCase() + ")";
        };
    }

    public static int instructionSize(int opcode) {
        return switch (opcode) {
            case LI -> 4;       // RI16: opcode + reg + imm16
            case MOV, NOT -> 2; // RR (packed registers)
            case LDBI, LDWI, STBI, STWI -> 2; // RR (packed registers, indirect addressing)
            case RET, HALT -> 1; // R (single byte, no operand)
            case LDB, LDW, STB, STW -> 4;  // MEM: opcode + reg + vaddr16
            case COPY, MOVE -> 8;          // BLOCK: opcode + srcVaddr16 + dstVaddr16 + len16
            case ADD, SUB, MUL, DIV, MOD,
                 AND, OR, XOR,
                 SHL, SHR, SAR,
                 CMP, CMPU -> 2;          // RR
            case JMP, JZ, JNZ, JC, JV, CALL -> 3; // BR16
            case HOST -> 2;                // HOST
            case TRAP -> 2;                // R + imm8
            default -> 0;
        };
    }
}
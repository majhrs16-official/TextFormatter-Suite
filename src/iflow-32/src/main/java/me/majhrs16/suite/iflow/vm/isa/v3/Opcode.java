package me.majhrs16.suite.iflow.vm.isa.v3;

import java.util.List;

/**
 * Valid opcodes in the v3 ISA, identified by family and operation.
 *
 * <p>The opcode box has two family bits and four operation bits. Combinations
 * absent from this enum are reserved and decode as
 * {@code ILLEGAL_INSTRUCTION}.</p>
 */
public enum Opcode {
    MOV(0, 0, "MOV", InstructionForm.REGISTER, InstructionForm.IMMEDIATE,
            InstructionForm.LOAD, InstructionForm.STORE),
    LEA(0, 1, "LEA", InstructionForm.LOAD),
    PUSH(0, 2, "PUSH", InstructionForm.REGISTER),
    POP(0, 3, "POP", InstructionForm.REGISTER),

    ADD(1, 0, "ADD", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    ADC(1, 1, "ADC", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    SUB(1, 2, "SUB", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    AND(1, 3, "AND", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    OR(1, 4, "OR", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    XOR(1, 5, "XOR", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    SHL(1, 6, "SHL", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    SHR(1, 7, "SHR", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    SAR(1, 8, "SAR", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    MUL(1, 9, "MUL", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    DIV(1, 10, "DIV", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    DIVU(1, 11, "DIVU", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    MOD(1, 12, "MOD", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    MODU(1, 13, "MODU", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    CMP(1, 14, "CMP", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    TEST(1, 15, "TEST", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),

    JMP(2, 0, "JMP", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    JCC(2, 1, "Jcc", InstructionForm.IMMEDIATE),
    CALL(2, 2, "CALL", InstructionForm.REGISTER, InstructionForm.IMMEDIATE),
    RET(2, 3, "RET", InstructionForm.REGISTER),
    ECALL(2, 4, "ECALL", InstructionForm.REGISTER),
    ERET(2, 5, "ERET", InstructionForm.REGISTER),
    HALT(2, 6, "HALT", InstructionForm.REGISTER),
    BREAK(2, 7, "BREAK", InstructionForm.REGISTER),
    NOP(2, 8, "NOP", InstructionForm.REGISTER),

    HOST(3, 0, "HOST", InstructionForm.REGISTER),
    RDCYCLE(3, 1, "RDCYCLE", InstructionForm.REGISTER),
    RDTIME(3, 2, "RDTIME", InstructionForm.REGISTER),
    CSRRW(3, 3, "CSRRW", InstructionForm.IMMEDIATE),
    CSRRS(3, 4, "CSRRS", InstructionForm.IMMEDIATE),
    CSRRC(3, 5, "CSRRC", InstructionForm.IMMEDIATE),
    CSRR(3, 6, "CSRR", InstructionForm.IMMEDIATE),
    CSRW(3, 7, "CSRW", InstructionForm.IMMEDIATE),
    SFENCE_VMA(3, 8, "SFENCE.VMA", InstructionForm.REGISTER),
    FENCE(3, 9, "FENCE", InstructionForm.REGISTER),
    LR_W(3, 10, "LR.W", InstructionForm.LOAD),
    SC_W(3, 11, "SC.W", InstructionForm.STORE),
    AMOADD_W(3, 12, "AMOADD.W", InstructionForm.STORE),
    AMOSWAP_W(3, 13, "AMOSWAP.W", InstructionForm.STORE),
    AMOAND_W(3, 14, "AMOAND.W", InstructionForm.STORE),
    AMOOR_W(3, 15, "AMOOR.W", InstructionForm.STORE);

    private static final Opcode[][] BY_FAMILY = new Opcode[4][16];

    static {
        for (Opcode opcode : values()) {
            BY_FAMILY[opcode.family][opcode.operation] = opcode;
        }
    }

    private final int family;
    private final int operation;
    private final String mnemonic;
    private final List<InstructionForm> forms;

    Opcode(int family, int operation, String mnemonic, InstructionForm... forms) {
        this.family = family;
        this.operation = operation;
        this.mnemonic = mnemonic;
        this.forms = List.of(forms);
    }

    public int family() {
        return family;
    }

    public int operation() {
        return operation;
    }

    public String mnemonic() {
        return mnemonic;
    }

    public List<InstructionForm> forms() {
        return forms;
    }

    public boolean supports(InstructionForm form) {
        return forms.contains(form);
    }

    /**
     * @return {@code true} when {@code rs} is the value operand instead of an
     *         index register, as in {@code SC.W} and {@code AMO*.W}
     */
    public boolean usesRsAsOperand() {
        return switch (this) {
            case SC_W, AMOADD_W, AMOSWAP_W, AMOAND_W, AMOOR_W -> true;
            default -> false;
        };
    }

    public static Opcode find(int family, int operation) {
        if (family < 0 || family >= BY_FAMILY.length
                || operation < 0 || operation >= BY_FAMILY[family].length) {
            return null;
        }
        return BY_FAMILY[family][operation];
    }
}

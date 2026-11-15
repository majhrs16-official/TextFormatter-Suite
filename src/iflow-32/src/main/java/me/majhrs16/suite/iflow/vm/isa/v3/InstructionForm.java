package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Encoding form selected by the low two bits of the opcode box.
 */
public enum InstructionForm {
    REGISTER(0, 3),
    IMMEDIATE(1, 7),
    LOAD(2, 7),
    STORE(3, 7);

    private static final InstructionForm[] BY_CODE = values();

    private final int code;
    private final int length;

    InstructionForm(int code, int length) {
        this.code = code;
        this.length = length;
    }

    public int code() {
        return code;
    }

    public int length() {
        return length;
    }

    public boolean isMemory() {
        return this == LOAD || this == STORE;
    }

    public static InstructionForm fromCode(int code) {
        if (code < 0 || code >= BY_CODE.length) {
            throw new IllegalArgumentException("Invalid instruction form: " + code);
        }
        return BY_CODE[code];
    }
}

package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Access size encoded in descriptor bits 2:1.
 */
public enum MemorySize {
    BYTE(0, 1),
    HALF(1, 2),
    WORD(2, 4);

    private static final MemorySize[] BY_CODE = values();

    private final int code;
    private final int bytes;

    MemorySize(int code, int bytes) {
        this.code = code;
        this.bytes = bytes;
    }

    public int code() {
        return code;
    }

    public int bytes() {
        return bytes;
    }

    public static MemorySize fromCode(int code) {
        if (code < 0 || code >= BY_CODE.length) {
            throw new IllegalArgumentException("Invalid memory size: " + code);
        }
        return BY_CODE[code];
    }
}

package me.majhrs16.suite.iflow.vm.rom;

/**
 * TOC segment types defined by the v3 ROM contract.
 */
public enum RomSegmentType {

    BIOS(1),
    BOOT(2),
    KERNEL(3),
    PROGRAM(4),
    DATA(5),
    FONT(6),
    CONFIG(7);

    private final int code;

    RomSegmentType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static RomSegmentType fromCode(int code) {
        for (RomSegmentType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown ROM segment type: " + code);
    }
}

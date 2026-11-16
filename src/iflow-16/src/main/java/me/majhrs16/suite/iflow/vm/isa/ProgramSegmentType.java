package me.majhrs16.suite.iflow.vm.isa;

/**
 * Segment types of the 16-bit ROM image (format 2, layout revision 1).
 * <p>
 * The numeric codes are part of the on-disk TOC. They are stable and must not
 * be renumbered. {@link #isLoadable()} distinguishes memory segments from
 * metadata sections; {@link #isExecutableType()} distinguishes code from data.
 * </p>
 */
public enum ProgramSegmentType {

    /** BIOS reset/POST and firmware helpers. Loaded at 0x0000, R/X, kernel-owned. */
    BIOS(1, true, true, false),

    /** Optional bootloader stage. Loaded at a page-aligned image address, R/X, kernel-owned. */
    BOOTLOADER(2, true, true, false),

    /** Optional kernel stage. Loaded at a page-aligned image address, R/X, kernel-owned. */
    KERNEL(3, true, true, false),

    /** User program. Loaded at a page-aligned image address, R/X, user-owned. */
    PROGRAM(4, true, true, true),

    /** Read-only constants. Loaded at 0x2000, R, kernel-owned. */
    CONSTANTS(5, true, false, false),

    /** NUL-terminated capability strings. Metadata, never loaded. */
    CAPABILITIES(6, false, false, false),

    /** UTF-8 debug information. Metadata, never loaded. */
    DEBUG(7, false, false, false);

    private final int code;
    private final boolean loadable;
    private final boolean executable;
    private final boolean userOwned;

    ProgramSegmentType(int code, boolean loadable, boolean executable, boolean userOwned) {
        this.code = code;
        this.loadable = loadable;
        this.executable = executable;
        this.userOwned = userOwned;
    }

    public int code() {
        return code;
    }

    public boolean isLoadable() {
        return loadable;
    }

    public boolean isExecutableType() {
        return executable;
    }

    public boolean isUserOwned() {
        return userOwned;
    }

    /** Returns the type for a TOC code, or {@code null} when the code is unknown. */
    public static ProgramSegmentType fromCode(int code) {
        for (ProgramSegmentType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}

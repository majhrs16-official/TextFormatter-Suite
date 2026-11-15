package me.majhrs16.suite.iflow.vm.irq;

/**
 * Layout of the interrupt vector table the BIOS publishes in its NVRAM (D33).
 * <p>
 * The emulator does not hard-code handlers: the BIOS writes a small, versioned
 * table into its NVRAM window and the runtime reads it to resolve the handler
 * address for a raised line. The table lives in device memory (NVRAM), not in the
 * program's RAM, so ordinary memory traffic never corrupts it:
 * </p>
 * <pre>
 *   +0x00  boot flag        (D32: the BIOS cold/warm marker)
 *   +0x02  magic            {@link #MAGIC}
 *   +0x04  count            number of valid entries
 *   +0x06  entry 0          16-bit handler address
 *   +0x08  entry 1
 *   ...
 * </pre>
 * <p>
 * A handler address of 0 (or a missing/invalid magic) means "no handler"; the
 * runtime then drops the line instead of dispatching.
 * </p>
 */
public final class IrqTable {

    /** Marks a valid table; also a version tag. */
    public static final int MAGIC = 0x1A7B;

    /** Table field offsets, relative to the NVRAM base. */
    public static final int OFFSET_MAGIC = 0x02;
    public static final int OFFSET_COUNT = 0x04;
    public static final int OFFSET_ENTRIES = 0x06;

    /** Maximum number of lines the table can hold (16-bit addresses). */
    public static final int MAX_ENTRIES = 16;

    /** Offset (relative to the NVRAM base) of the entry for {@code irq}. */
    public static int entryOffset(int irq) {
        return OFFSET_ENTRIES + 2 * irq;
    }

    private IrqTable() {
    }
}

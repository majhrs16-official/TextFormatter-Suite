package me.majhrs16.suite.iflow.vm.memory;

/**
 * A non-volatile RAM (NVRAM) window exposed as a bus slave inside the device
 * MMIO aperture (D32).
 * <p>
 * The BIOS uses it as <em>persistent scratch</em>: because the engine keeps a
 * single machine per program (D30), the attached peripheral instance lives for
 * the machine's whole life, so a value written while handling one message is
 * still there when the next message arrives. The reset routine relies on this
 * to tell a cold boot (first message, NVRAM erased) from a warm one, clearing
 * the volatile RAM exactly once instead of on every message.
 * </p>
 * <p>
 * It is plain storage, not a register bank: word and block transactions fall
 * back to the default byte-wise access of {@link VmDevice}. Addresses are
 * relative to {@link VmMemoryMap#DEVICE_NVRAM_BASE} and, like every device, are
 * decoded by the interconnect <em>before</em> the MMU.
 * </p>
 */
public final class VmNvram implements VmDevice {

    private final int base;
    private final byte[] cells;

    /** Creates the standard BIOS NVRAM window (see {@link VmMemoryMap}). */
    public VmNvram() {
        this(VmMemoryMap.DEVICE_NVRAM_BASE, VmMemoryMap.DEVICE_NVRAM_SIZE);
    }

    /**
     * Creates an NVRAM window of {@code size} bytes at {@code base}.
     *
     * @throws IllegalArgumentException if {@code size} is not positive
     */
    public VmNvram(int base, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("NVRAM size must be positive: " + size);
        }
        this.base = base;
        this.cells = new byte[size];
    }

    /** The device's base address in the flat bus space. */
    public int base() {
        return base;
    }

    /** The device's window size in bytes. */
    public int size() {
        return cells.length;
    }

    @Override
    public boolean decodes(int vaddr) {
        return vaddr >= base && vaddr < base + cells.length;
    }

    @Override
    public int readByte(int vaddr) {
        return cells[vaddr - base] & 0xFF;
    }

    @Override
    public void writeByte(int vaddr, int value) {
        cells[vaddr - base] = (byte) value;
    }
}

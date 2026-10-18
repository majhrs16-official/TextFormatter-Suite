package me.majhrs16.suite.iflow.vm.memory;

/**
 * A bus <em>slave</em> that decodes an address range of its own and answers
 * read/write transactions, alongside the memory slave ({@link SoftMMU}).
 * <p>
 * The interconnect ({@link VmBus}) asks every attached device whether it owns a
 * virtual address before delegating to RAM: a device access is a memory-mapped
 * I/O transaction and, like real MMIO, it is <em>not</em> translated or
 * permission-checked by the MMU (the device is addressed in the flat bus space).
 * This is what lets a peripheral (the text screen, {@code VmDisplay}) be mapped
 * into the program's address space and see every write the CPU or the DMA makes
 * to its window.
 * </p>
 * <p>
 * Only {@link #decodes(int)}, {@link #readByte(int)} and
 * {@link #writeByte(int, int)} are required; word and block transactions default
 * to byte-wise access, which is enough for register-style and cell-window
 * devices. Block access is the rare path (the hot I/O buffers are RAM, not
 * devices).
 * </p>
 */
public interface VmDevice {

    /** True if this device owns {@code vaddr} (its window or register bank). */
    boolean decodes(int vaddr);

    /** Reads one byte from the device at {@code vaddr}. */
    int readByte(int vaddr);

    /** Writes one byte to the device at {@code vaddr}. */
    void writeByte(int vaddr, int value);

    /** Reads a 16-bit little-endian word (two byte transactions). */
    default int readWord(int vaddr) {
        return (readByte(vaddr) & 0xFF) | ((readByte(vaddr + 1) & 0xFF) << 8);
    }

    /** Writes a 16-bit little-endian word (two byte transactions). */
    default void writeWord(int vaddr, int value) {
        writeByte(vaddr, value & 0xFF);
        writeByte(vaddr + 1, (value >> 8) & 0xFF);
    }

    /** Reads a block byte-by-byte. */
    default void readBlock(int vaddr, byte[] dest, int destOffset, int length) {
        for (int i = 0; i < length; i++) {
            dest[destOffset + i] = (byte) readByte(vaddr + i);
        }
    }

    /** Writes a block byte-by-byte. */
    default void writeBlock(int vaddr, byte[] src, int srcOffset, int length) {
        for (int i = 0; i < length; i++) {
            writeByte(vaddr + i, src[srcOffset + i] & 0xFF);
        }
    }

    /** Zeroes a block byte-by-byte. */
    default void clearBlock(int vaddr, int length) {
        for (int i = 0; i < length; i++) {
            writeByte(vaddr + i, 0);
        }
    }
}

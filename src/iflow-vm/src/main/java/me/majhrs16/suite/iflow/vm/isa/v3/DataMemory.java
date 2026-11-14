package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Data-side memory port used by the v3 CPU.
 *
 * <p>Addresses are 32-bit values, interpreted as unsigned. Implementations
 * perform translation, permission checks and bus decoding, and throw
 * {@link MemoryFault} when an access cannot be completed. A read of 8 or 16
 * bits returns a zero-extended value; a write stores the low bits.</p>
 */
public interface DataMemory {

    int read(int address, MemorySize size);

    void write(int address, MemorySize size, int value);

    /**
     * Orders memory, MMIO and DMA accesses before and after the fence.
     */
    void fence();

    /**
     * Invalidates any address-translation cache after a page-table change.
     */
    void flushTlb();
}

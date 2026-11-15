package me.majhrs16.suite.iflow.vm.memory.v3;

/**
 * A 4 KiB memory-mapped device window of the v3 bus.
 *
 * <p>Registers are 32-bit and little-endian. The bus validates supervisor mode,
 * word size and 4-byte alignment before calling the device; devices only see
 * register offsets inside their own window.</p>
 */
public interface MmioDevice {

    int WINDOW_SIZE = 0x1000;

    int base();

    default int size() {
        return WINDOW_SIZE;
    }

    default boolean decodes(int address) {
        long offset = Integer.toUnsignedLong(address) - Integer.toUnsignedLong(base());
        return offset >= 0 && offset < size();
    }

    int readRegister(int offset);

    void writeRegister(int offset, int value);
}

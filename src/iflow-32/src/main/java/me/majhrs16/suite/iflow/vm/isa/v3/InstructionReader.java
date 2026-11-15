package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Byte source used by the instruction decoder.
 *
 * <p>Implementations are responsible for instruction-fetch permissions, page
 * crossing and bus decoding. They may throw {@link MachineFault} when a byte
 * cannot be fetched; the decoder does not hide that fault.</p>
 */
@FunctionalInterface
public interface InstructionReader {

    /**
     * Reads one byte from the given 32-bit address.
     *
     * @return the byte value in the range 0..255
     */
    int readByte(int address);
}

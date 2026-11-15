package me.majhrs16.suite.iflow.vm.isa.v3;

import java.util.Objects;

/**
 * Register snapshot passed to a {@code HOST} handler.
 *
 * <p>The handler owns the service-specific ABI work: it reads arguments from
 * {@link #memory()} and writes the result into the guest buffer. The CPU only
 * supplies the registers and installs the returned status and length in
 * {@code R0}/{@code R1}.</p>
 */
public record HostCall(
        int pc,
        Privilege privilege,
        int serviceId,
        int flags,
        int argumentAddress,
        int argumentLength,
        int resultAddress,
        int resultCapacity,
        DataMemory memory,
        int pident,
        int mident
) {

    public static final int MAX_BUFFER = 1024 * 1024;

    public HostCall {
        Objects.requireNonNull(privilege, "privilege");
        Objects.requireNonNull(memory, "memory");
    }

    public byte[] argumentBytes() {
        if (argumentLength == 0) {
            return new byte[0];
        }
        if (argumentLength < 0 || argumentLength > MAX_BUFFER) {
            throw new IllegalStateException("Invalid HOST argument length: " + argumentLength);
        }
        byte[] arguments = new byte[argumentLength];
        for (int index = 0; index < arguments.length; index++) {
            long address = Integer.toUnsignedLong(argumentAddress) + index;
            if (address > 0xFFFF_FFFFL) {
                throw MemoryFault.load(argumentAddress,
                        "argument buffer crosses the 32-bit address space");
            }
            arguments[index] = (byte) memory.read((int) address, MemorySize.BYTE);
        }
        return arguments;
    }
}

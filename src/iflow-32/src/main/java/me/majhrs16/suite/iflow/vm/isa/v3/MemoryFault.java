package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Memory fault raised by a {@link DataMemory} implementation.
 *
 * <p>The memory does not know the instruction address, so it reports the
 * architectural cause and the bad address. The CPU attaches the current
 * instruction PC when it converts the fault into a {@link MachineFault}.</p>
 */
public final class MemoryFault extends RuntimeException {

    private final MachineFault.Code code;
    private final int badAddress;
    private final String detail;

    public MemoryFault(MachineFault.Code code, int badAddress, String detail) {
        super(format(code, badAddress, detail));
        if (code != MachineFault.Code.LOAD_FAULT
                && code != MachineFault.Code.STORE_FAULT
                && code != MachineFault.Code.INSTRUCTION_FAULT
                && code != MachineFault.Code.ALIGNMENT
                && code != MachineFault.Code.BUS_ERROR
                && code != MachineFault.Code.PRIVILEGE) {
            throw new IllegalArgumentException("Not a memory fault: " + code);
        }
        this.code = code;
        this.badAddress = badAddress;
        this.detail = detail;
    }

    public MachineFault.Code code() {
        return code;
    }

    public int badAddress() {
        return badAddress;
    }

    public String detail() {
        return detail;
    }

    public static MemoryFault load(int address, String detail) {
        return new MemoryFault(MachineFault.Code.LOAD_FAULT, address, detail);
    }

    public static MemoryFault store(int address, String detail) {
        return new MemoryFault(MachineFault.Code.STORE_FAULT, address, detail);
    }

    public static MemoryFault busError(int address, String detail) {
        return new MemoryFault(MachineFault.Code.BUS_ERROR, address, detail);
    }

    public static MemoryFault instructionFault(int address, String detail) {
        return new MemoryFault(MachineFault.Code.INSTRUCTION_FAULT, address, detail);
    }

    public static MemoryFault alignment(int address, String detail) {
        return new MemoryFault(MachineFault.Code.ALIGNMENT, address, detail);
    }

    public static MemoryFault privilege(int address, String detail) {
        return new MemoryFault(MachineFault.Code.PRIVILEGE, address, detail);
    }

    private static String format(MachineFault.Code code, int badAddress, String detail) {
        StringBuilder message = new StringBuilder(code.name())
                .append(" address=0x")
                .append(Integer.toHexString(badAddress));
        if (detail != null && !detail.isBlank()) {
            message.append(": ").append(detail);
        }
        return message.toString();
    }
}

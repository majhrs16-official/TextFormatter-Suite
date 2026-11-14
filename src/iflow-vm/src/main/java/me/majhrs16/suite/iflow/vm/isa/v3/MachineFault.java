package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Trap delivered by the v3 machine.
 *
 * <p>The vector is the architectural exception code defined by ADR-037. A
 * synchronous fault carries the address of the instruction that raised it;
 * instruction-fetch faults may also carry the address that could not be read.
 * </p>
 */
public final class MachineFault extends RuntimeException {

    public enum Code {
        ILLEGAL_INSTRUCTION(0),
        BREAKPOINT(1),
        LOAD_FAULT(2),
        STORE_FAULT(3),
        INSTRUCTION_FAULT(4),
        ALIGNMENT(5),
        PRIVILEGE(6),
        ECALL_USER(7),
        ECALL_SUPERVISOR(8),
        DIVIDE_BY_ZERO(9),
        ARITHMETIC_OVERFLOW(10),
        BUS_ERROR(11),
        DOUBLE_FAULT(14);

        private final int vector;

        Code(int vector) {
            this.vector = vector;
        }

        public int vector() {
            return vector;
        }
    }

    private final Code code;
    private final int pc;
    private final Integer badAddress;

    public MachineFault(Code code, int pc, String message) {
        this(code, pc, null, message);
    }

    public MachineFault(Code code, int pc, Integer badAddress, String message) {
        super(format(code, pc, badAddress, message));
        this.code = code;
        this.pc = pc;
        this.badAddress = badAddress;
    }

    public Code code() {
        return code;
    }

    public int pc() {
        return pc;
    }

    public Integer badAddress() {
        return badAddress;
    }

    public int vector() {
        return code.vector();
    }

    public static MachineFault illegalInstruction(int pc, String detail) {
        return new MachineFault(Code.ILLEGAL_INSTRUCTION, pc, detail);
    }

    public static MachineFault instructionFault(int pc, String detail) {
        return new MachineFault(Code.INSTRUCTION_FAULT, pc, detail);
    }

    public static MachineFault divideByZero(int pc) {
        return new MachineFault(Code.DIVIDE_BY_ZERO, pc, "division by zero");
    }

    public static MachineFault arithmeticOverflow(int pc, String detail) {
        return new MachineFault(Code.ARITHMETIC_OVERFLOW, pc, detail);
    }

    private static String format(Code code, int pc, Integer badAddress, String detail) {
        StringBuilder message = new StringBuilder(code.name())
                .append(" at PC=0x")
                .append(Integer.toHexString(pc));
        if (badAddress != null) {
            message.append(" address=0x").append(Integer.toHexString(badAddress));
        }
        if (detail != null && !detail.isBlank()) {
            message.append(": ").append(detail);
        }
        return message.toString();
    }
}

package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Architectural {@code STATUS} register.
 *
 * <p>Bits 0..5 are defined by ADR-037: IE, PRV, PIE, PPRV, MMU and TRAP. All
 * other bits are reserved and must be zero when the register is written.</p>
 */
public record Status(
        boolean interruptEnable,
        boolean supervisor,
        boolean previousInterruptEnable,
        boolean previousSupervisor,
        boolean mmuEnabled,
        boolean trap
) {

    public static final int IE = 0x01;
    public static final int PRV = 0x02;
    public static final int PIE = 0x04;
    public static final int PPRV = 0x08;
    public static final int MMU = 0x10;
    public static final int TRAP = 0x20;
    public static final int MASK = 0x3F;

    public static final Status RESET = new Status(false, true, false, false, false, false);

    public static boolean isValidBits(int bits) {
        return (bits & ~MASK) == 0;
    }

    public static Status fromBits(int bits) {
        if (!isValidBits(bits)) {
            throw new IllegalArgumentException("Reserved STATUS bits: 0x" + Integer.toHexString(bits));
        }
        return new Status(
                (bits & IE) != 0,
                (bits & PRV) != 0,
                (bits & PIE) != 0,
                (bits & PPRV) != 0,
                (bits & MMU) != 0,
                (bits & TRAP) != 0);
    }

    public int bits() {
        int bits = 0;
        if (interruptEnable) {
            bits |= IE;
        }
        if (supervisor) {
            bits |= PRV;
        }
        if (previousInterruptEnable) {
            bits |= PIE;
        }
        if (previousSupervisor) {
            bits |= PPRV;
        }
        if (mmuEnabled) {
            bits |= MMU;
        }
        if (trap) {
            bits |= TRAP;
        }
        return bits;
    }

    public Privilege privilege() {
        return supervisor ? Privilege.SUPERVISOR : Privilege.USER;
    }

    public Status enterTrap() {
        return new Status(false, true, interruptEnable, supervisor, mmuEnabled, true);
    }

    public Status eret() {
        return new Status(
                previousInterruptEnable,
                previousSupervisor,
                previousInterruptEnable,
                previousSupervisor,
                mmuEnabled,
                false);
    }
}

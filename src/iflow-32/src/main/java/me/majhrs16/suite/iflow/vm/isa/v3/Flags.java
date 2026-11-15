package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Architectural condition flags: Z (bit 0), N (bit 1), C (bit 2) and V (bit 3).
 */
public record Flags(boolean zero, boolean negative, boolean carry, boolean overflow) {

    public static final Flags CLEAR = new Flags(false, false, false, false);

    public static Flags fromResult(int result, boolean carry, boolean overflow) {
        return new Flags(result == 0, result < 0, carry, overflow);
    }

    public static Flags fromResult(int result) {
        return new Flags(result == 0, result < 0, false, false);
    }

    public int bits() {
        int bits = 0;
        if (zero) {
            bits |= 0x01;
        }
        if (negative) {
            bits |= 0x02;
        }
        if (carry) {
            bits |= 0x04;
        }
        if (overflow) {
            bits |= 0x08;
        }
        return bits;
    }

    public static Flags fromBits(int bits) {
        int value = bits & 0x0F;
        return new Flags(
                (value & 0x01) != 0,
                (value & 0x02) != 0,
                (value & 0x04) != 0,
                (value & 0x08) != 0);
    }
}

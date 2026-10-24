package me.majhrs16.suite.iflow.vm.isa;

/**
 * VM Register file with 8 general-purpose registers and control registers.
 * <p>
 * All registers are 16-bit unsigned values. Arithmetic operations use
 * two's complement interpretation for signed operations.
 * </p>
 */
public final class RegisterFile {

    // General-purpose registers (R0–R7)
    public static final int R0 = 0;
    public static final int R1 = 1;
    public static final int R2 = 2;
    public static final int R3 = 3;
    public static final int R4 = 4;
    public static final int R5 = 5;
    public static final int R6 = 6;
    public static final int R7 = 7;

    // Control registers (not general-purpose)
    public static final int IP = 8;   // Instruction Pointer
    public static final int SP = 9;   // Stack Pointer
    public static final int FP = 10;  // Frame Pointer
    public static final int FL = 11;  // Flags

    public static final int REG_COUNT = 12; // Total registers (8 GPR + 4 control)

    private final int[] regs = new int[REG_COUNT];

    public RegisterFile() {
        // All registers initialized to 0
    }

    public int get(int index) {
        if (index < 0 || index >= REG_COUNT) {
            throw new IllegalArgumentException("Invalid register index: " + index);
        }
        return regs[index] & 0xFFFF;
    }

    public void set(int index, int value) {
        if (index < 0 || index >= REG_COUNT) {
            throw new IllegalArgumentException("Invalid register index: " + index);
        }
        regs[index] = value & 0xFFFF;
    }

    /** Returns true if the register is a general-purpose register (R0–R7). */
    public static boolean isGPR(int index) {
        return index >= R0 && index <= R7;
    }

    /** Returns true if the register is a control register (IP, SP, FP, FL). */
    public static boolean isControl(int index) {
        return index >= IP && index <= FL;
    }

    // ===== Flags access =====

    public static final int FLAG_Z = 0; // Zero
    public static final int FLAG_N = 1; // Negative (MSB)
    public static final int FLAG_C = 2; // Carry/Borrow
    public static final int FLAG_V = 3; // Overflow (signed)

    public boolean getFlag(int flagIndex) {
        return (regs[FL] & (1 << flagIndex)) != 0;
    }

    public void setFlag(int flagIndex, boolean value) {
        if (value) {
            regs[FL] |= (1 << flagIndex);
        } else {
            regs[FL] &= ~(1 << flagIndex);
        }
    }

    public void setZ(boolean value) { setFlag(FLAG_Z, value); }
    public void setN(boolean value) { setFlag(FLAG_N, value); }
    public void setC(boolean value) { setFlag(FLAG_C, value); }
    public void setV(boolean value) { setFlag(FLAG_V, value); }

    public boolean getZ() { return getFlag(FLAG_Z); }
    public boolean getN() { return getFlag(FLAG_N); }
    public boolean getC() { return getFlag(FLAG_C); }
    public boolean getV() { return getFlag(FLAG_V); }

    /** Updates Z and N flags based on a 16-bit result. */
    public void updateZN(int result) {
        int r = result & 0xFFFF;
        setZ(r == 0);
        setN((r & 0x8000) != 0);
    }

    /** Updates all arithmetic flags for ADD/SUB. */
    public void updateAddFlags(int a, int b, int result, boolean isSub) {
        int ra = a & 0xFFFF;
        int rb = b & 0xFFFF;
        int r = result & 0xFFFF;

        setZ(r == 0);
        setN((r & 0x8000) != 0);

        if (isSub) {
            // Borrow: unsigned a < unsigned b
            setC((ra & 0xFFFF) < (rb & 0xFFFF));
            // Overflow: signs differ and result sign differs from a
            boolean aNeg = (ra & 0x8000) != 0;
            boolean bNeg = (rb & 0x8000) != 0;
            boolean rNeg = (r & 0x8000) != 0;
            setV(aNeg != bNeg && rNeg != aNeg);
        } else {
            // Carry: unsigned overflow
            setC(((ra & 0xFFFF) + (rb & 0xFFFF)) > 0xFFFF);
            // Overflow: signs same and result sign differs
            boolean aNeg = (ra & 0x8000) != 0;
            boolean bNeg = (rb & 0x8000) != 0;
            boolean rNeg = (r & 0x8000) != 0;
            setV(aNeg == bNeg && rNeg != aNeg);
        }
    }

    /** Updates flags for MUL. */
    public void updateMulFlags(int a, int b, int result) {
        int r = result & 0xFFFF;
        setZ(r == 0);
        setN((r & 0x8000) != 0);
        // Carry if high 16 bits non-zero
        long full = (a & 0xFFFFL) * (b & 0xFFFFL);
        setC((full >> 16) != 0);
        // Overflow for signed multiply
        int sa = (short) a;
        int sb = (short) b;
        int sr = (short) r;
        setV((long) sa * sb != sr);
    }

    /** Updates flags for DIV/MOD. */
    public void updateDivFlags(int result) {
        int r = result & 0xFFFF;
        setZ(r == 0);
        setN((r & 0x8000) != 0);
        setC(false);
        setV(false);
    }

    /** Updates flags for shift operations. */
    public void updateShiftFlags(int result, int lastBitShiftedOut) {
        int r = result & 0xFFFF;
        setZ(r == 0);
        setN((r & 0x8000) != 0);
        setC(lastBitShiftedOut == 1);
        setV(false);
    }

    /** Updates flags for logic operations (AND, OR, XOR, NOT). */
    public void updateLogicFlags(int result) {
        int r = result & 0xFFFF;
        setZ(r == 0);
        setN((r & 0x8000) != 0);
        setC(false);
        setV(false);
    }

    /** Updates flags for CMP (signed comparison via subtraction). */
    public void updateCmpFlags(int a, int b) {
        int ra = (short) a;
        int rb = (short) b;
        int r = ra - rb;
        updateAddFlags(a, b, r, true);
    }

    /** Updates flags for CMPU (unsigned comparison via subtraction). */
    public void updateCmpuFlags(int a, int b) {
        int ra = a & 0xFFFF;
        int rb = b & 0xFFFF;
        int r = (ra - rb) & 0xFFFF;
        setZ(r == 0);
        setN((r & 0x8000) != 0);
        setC(ra < rb);
        setV(false);
    }

    public void clearFlags() {
        regs[FL] = 0;
    }

    public int getFlags() {
        return regs[FL] & 0xFFFF;
    }

    public void setFlags(int flags) {
        regs[FL] = flags & 0xFFFF;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("R0-R7: ");
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("R%d=0x%04X ", i, regs[i]));
        }
        sb.append(String.format("IP=0x%04X SP=0x%04X FP=0x%04X FL=0x%04X",
            regs[IP], regs[SP], regs[FP], regs[FL]));
        return sb.toString();
    }
}
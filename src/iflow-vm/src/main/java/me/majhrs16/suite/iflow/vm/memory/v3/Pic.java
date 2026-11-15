package me.majhrs16.suite.iflow.vm.memory.v3;

/**
 * Edge-triggered v3 programmable interrupt controller (ADR-037 §5.9).
 *
 * <p>Thirty-two lines, active when pending and unmasked. Taking a line clears
 * its pending bit and marks it in service; a new edge while the line is in
 * service sets pending again but the line is not taken until {@code ACK}
 * removes it from service. The lowest-numbered eligible line wins.</p>
 */
public final class Pic implements MmioDevice {

    public static final int BASE = 0x0100_2000;
    public static final int LINE_COUNT = 32;

    public static final int PENDING = 0x00;
    public static final int MASK = 0x04;
    public static final int ACK = 0x08;
    public static final int IN_SERVICE = 0x0C;
    public static final int SOFT_IRQ = 0x10;

    private int pending;
    private int mask = 0xFFFF_FFFF;
    private int inService;

    @Override
    public int base() {
        return BASE;
    }

    public void reset() {
        pending = 0;
        mask = 0xFFFF_FFFF;
        inService = 0;
    }

    /**
     * Raises one line from a device edge.
     */
    public void raise(int line) {
        checkLine(line);
        pending |= 1 << line;
    }

    /**
     * Raises the lines selected by a software mask.
     */
    public void raiseMask(int lines) {
        pending |= lines;
    }

    /**
     * Consumes the lowest-numbered pending, unmasked, non-serviced line.
     *
     * @return the line, or {@link #NO_LINE} when nothing is eligible
     */
    public int takePending() {
        int eligible = pending & ~mask & ~inService;
        if (eligible == 0) {
            return NO_LINE;
        }
        int line = Integer.numberOfTrailingZeros(eligible);
        pending &= ~(1 << line);
        inService |= 1 << line;
        return line;
    }

    public static final int NO_LINE = -1;

    public void acknowledge(int lines) {
        inService &= ~lines;
    }

    public int pending() {
        return pending;
    }

    public int mask() {
        return mask;
    }

    public int inService() {
        return inService;
    }

    public void setMask(int value) {
        mask = value;
    }

    public static int lineBit(int line) {
        checkLine(line);
        return 1 << line;
    }

    @Override
    public int readRegister(int offset) {
        return switch (offset) {
            case PENDING -> pending;
            case MASK -> mask;
            case ACK -> 0;
            case IN_SERVICE -> inService;
            case SOFT_IRQ -> 0;
            default -> throw new IllegalArgumentException(
                    "Unknown PIC register 0x" + Integer.toHexString(offset));
        };
    }

    @Override
    public void writeRegister(int offset, int value) {
        switch (offset) {
            case PENDING, IN_SERVICE -> {
                // Read-only registers.
            }
            case MASK -> mask = value;
            case ACK -> acknowledge(value);
            case SOFT_IRQ -> raiseMask(value);
            default -> throw new IllegalArgumentException(
                    "Unknown PIC register 0x" + Integer.toHexString(offset));
        }
    }

    private static void checkLine(int line) {
        if (line < 0 || line >= LINE_COUNT) {
            throw new IllegalArgumentException("IRQ line out of range: " + line);
        }
    }
}

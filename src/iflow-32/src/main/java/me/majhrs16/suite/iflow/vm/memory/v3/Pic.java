package me.majhrs16.suite.iflow.vm.memory.v3;

/**
 * Edge-triggered v3 programmable interrupt controller (ADR-037 §5.9).
 *
 * <p>The controller owns one bank per core. Each bank has thirty-two lines,
 * active when pending and unmasked. Taking a line clears its pending bit and
 * marks it in service; a new edge while the line is in service sets pending
 * again but the line is not taken until {@code ACK} removes it from service.
 * The lowest-numbered eligible line wins.</p>
 *
 * <p>{@code SOFT_IRQ} carries a line in {@code [7:0]} and a target core in
 * {@code [15:8]}; {@code 0xFF} broadcasts the line to every core. A target
 * outside {@code CORE_COUNT} is rejected and becomes {@code BUS_ERROR} at the
 * bus.</p>
 */
public final class Pic implements MmioDevice {

    public static final int BASE = 0x0100_2000;
    public static final int LINE_COUNT = 32;
    public static final int BROADCAST = 0xFF;
    public static final int MIN_CORE_COUNT = 1;
    public static final int MAX_CORE_COUNT = 64;

    public static final int PENDING = 0x00;
    public static final int MASK = 0x04;
    public static final int ACK = 0x08;
    public static final int IN_SERVICE = 0x0C;
    public static final int SOFT_IRQ = 0x10;

    public static final int NO_LINE = -1;

    private final int coreCount;
    private final int[] pending;
    private final int[] mask;
    private final int[] inService;

    public Pic() {
        this(1);
    }

    public Pic(int coreCount) {
        if (coreCount < MIN_CORE_COUNT || coreCount > MAX_CORE_COUNT) {
            throw new IllegalArgumentException(
                    "PIC core count must be " + MIN_CORE_COUNT + ".." + MAX_CORE_COUNT
                            + ": " + coreCount);
        }
        this.coreCount = coreCount;
        this.pending = new int[coreCount];
        this.mask = new int[coreCount];
        this.inService = new int[coreCount];
        reset();
    }

    @Override
    public int base() {
        return BASE;
    }

    public int coreCount() {
        return coreCount;
    }

    public synchronized void reset() {
        for (int core = 0; core < coreCount; core++) {
            pending[core] = 0;
            mask[core] = 0xFFFF_FFFF;
            inService[core] = 0;
        }
    }

    /**
     * Raises one line in the boot core bank.
     */
    public void raise(int line) {
        raise(0, line);
    }

    /**
     * Raises one line in a specific core bank.
     */
    public synchronized void raise(int coreId, int line) {
        checkCore(coreId);
        checkLine(line);
        pending[coreId] |= 1 << line;
    }

    /**
     * Raises one line in every core bank.
     */
    public synchronized void raiseAll(int line) {
        checkLine(line);
        int bit = 1 << line;
        for (int core = 0; core < coreCount; core++) {
            pending[core] |= bit;
        }
    }

    /**
     * Consumes the lowest-numbered pending, unmasked, non-serviced line of the
     * boot core.
     */
    public int takePending() {
        return takePending(0);
    }

    /**
     * Consumes the lowest-numbered pending, unmasked, non-serviced line of one
     * core bank.
     *
     * @return the line, or {@link #NO_LINE} when nothing is eligible
     */
    public synchronized int takePending(int coreId) {
        checkCore(coreId);
        int eligible = pending[coreId] & ~mask[coreId] & ~inService[coreId];
        if (eligible == 0) {
            return NO_LINE;
        }
        int line = Integer.numberOfTrailingZeros(eligible);
        pending[coreId] &= ~(1 << line);
        inService[coreId] |= 1 << line;
        return line;
    }

    public void acknowledge(int lines) {
        acknowledge(0, lines);
    }

    public synchronized void acknowledge(int coreId, int lines) {
        checkCore(coreId);
        inService[coreId] &= ~lines;
    }

    public int pending() {
        return pending(0);
    }

    public synchronized int pending(int coreId) {
        checkCore(coreId);
        return pending[coreId];
    }

    public int mask() {
        return mask(0);
    }

    public synchronized int mask(int coreId) {
        checkCore(coreId);
        return mask[coreId];
    }

    public int inService() {
        return inService(0);
    }

    public synchronized int inService(int coreId) {
        checkCore(coreId);
        return inService[coreId];
    }

    public void setMask(int value) {
        setMask(0, value);
    }

    public synchronized void setMask(int coreId, int value) {
        checkCore(coreId);
        mask[coreId] = value;
    }

    public static int lineBit(int line) {
        checkLine(line);
        return 1 << line;
    }

    @Override
    public int readRegister(int offset) {
        return readRegister(offset, 0);
    }

    @Override
    public synchronized int readRegister(int offset, int coreId) {
        checkCore(coreId);
        return switch (offset) {
            case PENDING -> pending[coreId];
            case MASK -> mask[coreId];
            case ACK -> 0;
            case IN_SERVICE -> inService[coreId];
            case SOFT_IRQ -> 0;
            default -> throw new IllegalArgumentException(
                    "Unknown PIC register 0x" + Integer.toHexString(offset));
        };
    }

    @Override
    public void writeRegister(int offset, int value) {
        writeRegister(offset, value, 0);
    }

    @Override
    public synchronized void writeRegister(int offset, int value, int coreId) {
        checkCore(coreId);
        switch (offset) {
            case PENDING, IN_SERVICE -> {
                // Read-only registers.
            }
            case MASK -> mask[coreId] = value;
            case ACK -> inService[coreId] &= ~value;
            case SOFT_IRQ -> writeSoftIrq(value);
            default -> throw new IllegalArgumentException(
                    "Unknown PIC register 0x" + Integer.toHexString(offset));
        }
    }

    private void writeSoftIrq(int value) {
        if ((value & ~0xFFFF) != 0) {
            throw new IllegalArgumentException(
                    "SOFT_IRQ only defines line[7:0] and target[15:8]: 0x"
                            + Integer.toHexString(value));
        }
        int line = value & 0xFF;
        int target = (value >>> 8) & 0xFF;
        if (line >= LINE_COUNT) {
            throw new IllegalArgumentException("IRQ line out of range: " + line);
        }
        if (target == BROADCAST) {
            raiseAll(line);
        } else {
            raise(target, line);
        }
    }

    private void checkCore(int coreId) {
        if (coreId < 0 || coreId >= coreCount) {
            throw new IllegalArgumentException(
                    "PIC core out of range: " + coreId + " (core-count " + coreCount + ")");
        }
    }

    private static void checkLine(int line) {
        if (line < 0 || line >= LINE_COUNT) {
            throw new IllegalArgumentException("IRQ line out of range: " + line);
        }
    }
}

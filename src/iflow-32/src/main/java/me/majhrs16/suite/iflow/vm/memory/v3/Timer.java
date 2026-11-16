package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.Objects;

import me.majhrs16.suite.iflow.vm.isa.v3.TimeSource;

/**
 * v3 timer/RTC at {@code 0x0100_1000} (ADR-037 §5.8).
 *
 * <p>The timer compares the virtual nanosecond clock with a 64-bit deadline. It
 * is one-shot by default and periodic when {@code CTRL.periodic} is set. A
 * periodic timer that missed several periods fires once and rearms from the
 * current time, so a paused VM cannot create an interrupt storm.</p>
 */
public final class Timer implements MmioDevice {

    public static final int BASE = 0x0100_1000;
    public static final int IRQ_LINE = 0;

    public static final int CMP_LO = 0x00;
    public static final int CMP_HI = 0x04;
    public static final int CTRL = 0x08;
    public static final int STATUS = 0x0C;
    public static final int PERIOD_LO = 0x10;
    public static final int PERIOD_HI = 0x14;
    public static final int TIME_LO = 0x18;
    public static final int TIME_HI = 0x1C;

    public static final int CTRL_ENABLE = 1;
    public static final int CTRL_PERIODIC = 1 << 1;
    public static final int CTRL_IRQ = 1 << 2;

    public static final int STATUS_FIRED = 1;

    private static final long DEFAULT_PERIOD = 1_000_000L;

    private final TimeSource timeSource;
    private final Pic pic;

    private long compare;
    private long period = DEFAULT_PERIOD;
    private int control;
    private boolean fired;
    private boolean armed;
    private Integer latchedHigh;

    public Timer(TimeSource timeSource, Pic pic) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.pic = Objects.requireNonNull(pic, "pic");
    }

    @Override
    public int base() {
        return BASE;
    }

    public void reset() {
        compare = 0;
        period = DEFAULT_PERIOD;
        control = 0;
        fired = false;
        armed = false;
        latchedHigh = null;
    }

    /**
     * Advances the timer to the current virtual time. Called by the bus before
     * decoding an MMIO access and by the CPU before checking interrupts.
     */
    public void tick() {
        if (!armed || !enabled()) {
            return;
        }
        long now = timeSource.timeNanos();
        if (Long.compareUnsigned(now, compare) < 0) {
            return;
        }
        fired = true;
        if ((control & CTRL_IRQ) != 0) {
            pic.raise(IRQ_LINE);
        }
        if ((control & CTRL_PERIODIC) != 0) {
            compare = now + period;
            armed = true;
        } else {
            armed = false;
        }
    }

    public long nextDeadline() {
        return armed && enabled() ? compare : Long.MAX_VALUE;
    }

    @Override
    public int readRegister(int offset) {
        return switch (offset) {
            case CMP_LO -> (int) compare;
            case CMP_HI -> (int) (compare >>> 32);
            case CTRL -> control;
            case STATUS -> fired ? STATUS_FIRED : 0;
            case PERIOD_LO -> (int) period;
            case PERIOD_HI -> (int) (period >>> 32);
            case TIME_LO -> {
                long now = timeSource.timeNanos();
                latchedHigh = (int) (now >>> 32);
                yield (int) now;
            }
            case TIME_HI -> {
                long now = timeSource.timeNanos();
                int high = latchedHigh != null
                        ? latchedHigh
                        : (int) (now >>> 32);
                latchedHigh = null;
                yield high;
            }
            default -> throw new IllegalArgumentException(
                    "Unknown timer register 0x" + Integer.toHexString(offset));
        };
    }

    @Override
    public void writeRegister(int offset, int value) {
        switch (offset) {
            case CMP_LO -> {
                compare = (compare & 0xFFFF_FFFF_0000_0000L) | Integer.toUnsignedLong(value);
                armIfEnabled();
            }
            case CMP_HI -> {
                compare = (compare & 0x0000_0000_FFFF_FFFFL)
                        | (Integer.toUnsignedLong(value) << 32);
                armIfEnabled();
            }
            case CTRL -> {
                boolean wasEnabled = enabled();
                control = value & (CTRL_ENABLE | CTRL_PERIODIC | CTRL_IRQ);
                if (!enabled()) {
                    armed = false;
                } else if (!wasEnabled) {
                    armed = true;
                }
            }
            case STATUS -> {
                if ((value & STATUS_FIRED) != 0) {
                    fired = false;
                }
            }
            case PERIOD_LO -> period = (period & 0xFFFF_FFFF_0000_0000L)
                    | Integer.toUnsignedLong(value);
            case PERIOD_HI -> period = (period & 0x0000_0000_FFFF_FFFFL)
                    | (Integer.toUnsignedLong(value) << 32);
            default -> throw new IllegalArgumentException(
                    "Unknown timer register 0x" + Integer.toHexString(offset));
        }
    }

    private void armIfEnabled() {
        if (enabled()) {
            armed = true;
        }
    }

    private boolean enabled() {
        return (control & CTRL_ENABLE) != 0;
    }
}

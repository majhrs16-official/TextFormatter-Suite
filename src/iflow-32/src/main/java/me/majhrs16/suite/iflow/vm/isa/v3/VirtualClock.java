package me.majhrs16.suite.iflow.vm.isa.v3;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic virtual nanosecond clock of a v3 VM.
 *
 * <p>The clock is a 64-bit unsigned counter. It advances during guest
 * execution and during guest waits, never reset except by a VM reset. Arithmetic
 * wraps modulo 2^64, matching the {@code RDTIME} contract in ADR-037 section
 * 6.1; comparisons are unsigned.</p>
 */
public final class VirtualClock implements TimeSource {

    private final AtomicLong nanos;

    public VirtualClock() {
        this(0);
    }

    public VirtualClock(long initialNanos) {
        nanos = new AtomicLong(initialNanos);
    }

    @Override
    public long timeNanos() {
        return nanos.get();
    }

    /**
     * Advances the clock by {@code delta} nanoseconds.
     *
     * @throws IllegalArgumentException when {@code delta} is negative
     */
    public void advanceNanos(long delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("Negative virtual time advance: " + delta);
        }
        nanos.addAndGet(delta);
    }

    /**
     * Advances the clock to an absolute deadline.
     *
     * <p>The comparison is unsigned: a deadline after the 64-bit wrap is still
     * valid. Moving backwards is rejected so that no caller can silently rewind
     * virtual time.</p>
     *
     * @throws IllegalArgumentException when the deadline is behind the current
     *                                  value in unsigned order
     */
    public void advanceTo(long deadlineNanos) {
        while (true) {
            long current = nanos.get();
            if (Long.compareUnsigned(deadlineNanos, current) < 0) {
                throw new IllegalArgumentException(
                        "Virtual time cannot move backwards: current=" + current
                                + ", deadline=" + deadlineNanos);
            }
            if (deadlineNanos == current || nanos.compareAndSet(current, deadlineNanos)) {
                return;
            }
        }
    }

    /**
     * Cold reset of the VM clock.
     */
    public void reset() {
        nanos.set(0);
    }
}

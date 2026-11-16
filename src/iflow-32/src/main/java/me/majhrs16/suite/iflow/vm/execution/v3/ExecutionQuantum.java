package me.majhrs16.suite.iflow.vm.execution.v3;

/**
 * Host-imposed preemption slice of one v3 vCPU.
 *
 * <p>This is not fuel, not a message budget and not a total execution limit. When the slice
 * expires, the host regains control, keeps all architectural state and the open journal, and may
 * resume the same vCPU immediately. The guest scheduler decides whether the work continues; the
 * host only guarantees that it cannot be masked.</p>
 *
 * @param maxInstructions maximum attempted instructions per slice
 * @param maxGuestNanos   maximum wall-clock nanoseconds charged to guest execution per slice;
 *                        time spent inside {@code HOST} is excluded
 */
public record ExecutionQuantum(long maxInstructions, long maxGuestNanos) {

    public static final long DEFAULT_MAX_INSTRUCTIONS = 1_000_000L;
    public static final long DEFAULT_MAX_GUEST_NANOS = 50_000_000L;

    public ExecutionQuantum {
        if (maxInstructions <= 0) {
            throw new IllegalArgumentException(
                    "maxInstructions must be positive: " + maxInstructions);
        }
        if (maxGuestNanos <= 0) {
            throw new IllegalArgumentException(
                    "maxGuestNanos must be positive: " + maxGuestNanos);
        }
    }

    public static ExecutionQuantum defaults() {
        return new ExecutionQuantum(DEFAULT_MAX_INSTRUCTIONS, DEFAULT_MAX_GUEST_NANOS);
    }
}

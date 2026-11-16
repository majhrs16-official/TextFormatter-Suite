package me.majhrs16.suite.iflow.vm.execution.v3;

/**
 * Host-imposed execution window of one v3 run.
 *
 * <p>The guest cannot read or disable this budget: it is not fuel and does not
 * appear in the ABI. A budget is always finite so a broken guest cannot remove
 * the watchdog.</p>
 *
 * @param maxInstructions maximum attempted instructions
 * @param maxGuestNanos   maximum wall-clock nanoseconds charged to guest
 *                        execution; time spent inside {@code HOST} is excluded
 */
public record ExecutionBudget(long maxInstructions, long maxGuestNanos) {

    public static final long DEFAULT_MAX_INSTRUCTIONS = 1_000_000L;
    public static final long DEFAULT_MAX_GUEST_NANOS = 50_000_000L;

    public ExecutionBudget {
        if (maxInstructions <= 0) {
            throw new IllegalArgumentException(
                    "maxInstructions must be positive: " + maxInstructions);
        }
        if (maxGuestNanos <= 0) {
            throw new IllegalArgumentException(
                    "maxGuestNanos must be positive: " + maxGuestNanos);
        }
    }

    public static ExecutionBudget defaults() {
        return new ExecutionBudget(DEFAULT_MAX_INSTRUCTIONS, DEFAULT_MAX_GUEST_NANOS);
    }
}

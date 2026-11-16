package me.majhrs16.suite.iflow.vm.execution.v3;

import java.util.Objects;

/**
 * Observable result of a host execution window.
 *
 * @param status       terminal state
 * @param steps        attempted instructions, including trap and IRQ delivery
 * @param cycles       retired instructions reported by the CPU
 * @param guestNanos   wall-clock nanoseconds charged to guest execution
 * @param virtualNanos virtual {@code RDTIME} value at the end of the window
 */
public record ExecutionReport(ExecutionStatus status,
                              long steps,
                              long cycles,
                              long guestNanos,
                              long virtualNanos) {

    public ExecutionReport {
        Objects.requireNonNull(status, "status");
        if (steps < 0) {
            throw new IllegalArgumentException("Negative step count: " + steps);
        }
        if (cycles < 0) {
            throw new IllegalArgumentException("Negative cycle count: " + cycles);
        }
        if (guestNanos < 0) {
            throw new IllegalArgumentException("Negative guest time: " + guestNanos);
        }
    }
}

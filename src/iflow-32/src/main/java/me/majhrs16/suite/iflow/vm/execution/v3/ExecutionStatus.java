package me.majhrs16.suite.iflow.vm.execution.v3;

/**
 * Terminal state of one host execution slice.
 *
 * <p>{@link #PREEMPTED} is a scheduling yield, not a failure and not a message timeout. The vCPU
 * and its open journal survive; the host may resume the same core immediately. {@link #WATCHDOG}
 * is the exceptional case: one guest instruction did not return within the host bound, so the
 * core is faulted instead of pretending progress.</p>
 */
public enum ExecutionStatus {

    /** The vCPU halted and no armed wake source remains. */
    HALTED,

    /** The quantum expired; the vCPU is resumable and no state was discarded. */
    PREEMPTED,

    /** A single guest step exceeded the non-maskable watchdog bound; the core is faulted. */
    WATCHDOG
}

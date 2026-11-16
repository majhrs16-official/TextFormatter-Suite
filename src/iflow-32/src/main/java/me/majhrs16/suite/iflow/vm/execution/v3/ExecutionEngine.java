package me.majhrs16.suite.iflow.vm.execution.v3;

import java.util.Objects;
import java.util.function.LongSupplier;

import me.majhrs16.suite.iflow.vm.isa.v3.Cpu;
import me.majhrs16.suite.iflow.vm.isa.v3.Opcode;
import me.majhrs16.suite.iflow.vm.isa.v3.VirtualClock;

/**
 * Host-side execution window of one v3 vCPU.
 *
 * <p>This class only enforces the quantum and the watchdog from ADR-037
 * section 6.5. It is not a guest scheduler: the kernel decides what runs next.
 * When the vCPU is halted, the engine advances virtual time to the next armed
 * deadline instead of busy-waiting and lets the timer raise its IRQ.</p>
 *
 * <p>Wall-clock time spent inside a {@code HOST} hypercall is not charged to
 * the guest watchdog; the action timeout bounds that call independently.</p>
 */
public final class ExecutionEngine {

    private static final long NANOS_PER_INSTRUCTION = 1L;

    private final Cpu cpu;
    private final VirtualClock clock;
    private final NanoClock nanoClock;
    private final LongSupplier nextDeadline;
    private final Runnable poll;

    public ExecutionEngine(Cpu cpu,
                           VirtualClock clock,
                           LongSupplier nextDeadline,
                           Runnable poll) {
        this(cpu, clock, System::nanoTime, nextDeadline, poll);
    }

    public ExecutionEngine(Cpu cpu,
                           VirtualClock clock,
                           NanoClock nanoClock,
                           LongSupplier nextDeadline,
                           Runnable poll) {
        this.cpu = Objects.requireNonNull(cpu, "cpu");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.nextDeadline = Objects.requireNonNull(nextDeadline, "nextDeadline");
        this.poll = Objects.requireNonNull(poll, "poll");
    }

    /**
     * Runs until the vCPU halts without a wake source or a host limit expires.
     *
     * <p>The limit is checked only at instruction boundaries. A budget abort
     * leaves the machine state advanced; reverting a message journal belongs to
     * the caller, not to this engine.</p>
     */
    public ExecutionReport run(ExecutionBudget budget) {
        Objects.requireNonNull(budget, "budget");
        long steps = 0;
        long guestNanos = 0;

        while (true) {
            if (steps >= budget.maxInstructions()) {
                return report(ExecutionStatus.QUANTUM_EXCEEDED, steps, guestNanos);
            }
            if (guestNanos >= budget.maxGuestNanos()) {
                return report(ExecutionStatus.HOST_TIMEOUT, steps, guestNanos);
            }

            Step step;
            if (cpu.halted()) {
                poll.run();
                step = executeStep();
                if (!step.attempted()) {
                    long deadline = nextDeadline.getAsLong();
                    if (deadline == Long.MAX_VALUE) {
                        return report(ExecutionStatus.HALTED, steps, guestNanos);
                    }
                    long now = clock.timeNanos();
                    if (Long.compareUnsigned(deadline, now) > 0) {
                        clock.advanceTo(deadline);
                    }
                    poll.run();
                    step = executeStep();
                    if (!step.attempted()) {
                        return report(ExecutionStatus.HALTED, steps, guestNanos);
                    }
                }
            } else {
                step = executeStep();
                if (!step.attempted()) {
                    continue;
                }
            }

            steps++;
            guestNanos = saturatingAdd(guestNanos, step.guestNanos());
            clock.advanceNanos(NANOS_PER_INSTRUCTION);

            if (steps >= budget.maxInstructions()) {
                return report(ExecutionStatus.QUANTUM_EXCEEDED, steps, guestNanos);
            }
            if (guestNanos >= budget.maxGuestNanos()) {
                return report(ExecutionStatus.HOST_TIMEOUT, steps, guestNanos);
            }
        }
    }

    private Step executeStep() {
        long start = nanoClock.nanoTime();
        boolean attempted = cpu.step();
        if (!attempted) {
            return Step.NOT_ATTEMPTED;
        }
        if (cpu.lastOpcode() == Opcode.HOST) {
            return new Step(true, 0L);
        }
        return new Step(true, Math.max(0L, nanoClock.nanoTime() - start));
    }

    private ExecutionReport report(ExecutionStatus status, long steps, long guestNanos) {
        return new ExecutionReport(status, steps, cpu.cycles(), guestNanos, clock.timeNanos());
    }

    private static long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private record Step(boolean attempted, long guestNanos) {

        private static final Step NOT_ATTEMPTED = new Step(false, 0L);
    }
}

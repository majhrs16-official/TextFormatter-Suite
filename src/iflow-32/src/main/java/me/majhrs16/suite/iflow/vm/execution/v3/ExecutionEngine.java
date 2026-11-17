package me.majhrs16.suite.iflow.vm.execution.v3;

import java.util.Objects;
import java.util.function.LongSupplier;

import me.majhrs16.suite.iflow.vm.isa.v3.Cpu;
import me.majhrs16.suite.iflow.vm.isa.v3.Opcode;
import me.majhrs16.suite.iflow.vm.isa.v3.VirtualClock;

/**
 * Host-side execution slice of one v3 vCPU.
 *
 * <p>This class enforces the quantum and the optional watchdog from ADR-037 section 6.5. The
 * quantum is non-maskable and always active. The watchdog is active by default and can be disabled
 * explicitly through {@link WatchdogConfig} for very slow hosts; disabling it never changes the
 * quantum. This class is not a guest scheduler: the kernel decides what runs next. A quantum expiry
 * returns {@link ExecutionStatus#PREEMPTED} and keeps the vCPU and its open journal resumable; it is
 * not a message timeout. Only a single guest step that exceeds the watchdog bound returns
 * {@link ExecutionStatus#WATCHDOG} and faults the core.</p>
 *
 * <p>When the vCPU is halted, the engine advances virtual time to the next armed deadline instead
 * of busy-waiting and lets the timer raise its IRQ.</p>
 *
 * <p>Wall-clock time spent inside a {@code HOST} hypercall is not charged to the guest quantum or
 * watchdog; the action timeout bounds that call independently.</p>
 */
public final class ExecutionEngine {

    private static final long NANOS_PER_INSTRUCTION = 1L;

    private final Cpu cpu;
    private final VirtualClock clock;
    private final NanoClock nanoClock;
    private final LongSupplier nextDeadline;
    private final Runnable poll;
    private final WatchdogConfig watchdog;

    public ExecutionEngine(Cpu cpu,
                           VirtualClock clock,
                           LongSupplier nextDeadline,
                           Runnable poll) {
        this(cpu, clock, System::nanoTime, nextDeadline, poll, WatchdogConfig.defaults());
    }

    public ExecutionEngine(Cpu cpu,
                           VirtualClock clock,
                           LongSupplier nextDeadline,
                           Runnable poll,
                           WatchdogConfig watchdog) {
        this(cpu, clock, System::nanoTime, nextDeadline, poll, watchdog);
    }

    public ExecutionEngine(Cpu cpu,
                           VirtualClock clock,
                           NanoClock nanoClock,
                           LongSupplier nextDeadline,
                           Runnable poll) {
        this(cpu, clock, nanoClock, nextDeadline, poll, WatchdogConfig.defaults());
    }

    public ExecutionEngine(Cpu cpu,
                           VirtualClock clock,
                           NanoClock nanoClock,
                           LongSupplier nextDeadline,
                           Runnable poll,
                           WatchdogConfig watchdog) {
        this.cpu = Objects.requireNonNull(cpu, "cpu");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.nextDeadline = Objects.requireNonNull(nextDeadline, "nextDeadline");
        this.poll = Objects.requireNonNull(poll, "poll");
        this.watchdog = Objects.requireNonNull(watchdog, "watchdog");
    }

    /** @return {@code true} si el watchdog de paso está activo. */
    public boolean watchdogEnabled() {
        return watchdog.enabled();
    }

    /**
     * Runs until the vCPU halts without a wake source, the quantum expires, or the watchdog fires.
     *
     * <p>The limit is checked only at instruction boundaries. {@code PREEMPTED} leaves the machine
     * state and the open journal intact; reverting or committing a message journal belongs to the
     * caller. {@code WATCHDOG} means one guest instruction did not return within the bound and the
     * core must not be treated as healthy.</p>
     */
    public ExecutionReport run(ExecutionQuantum quantum) {
        Objects.requireNonNull(quantum, "quantum");
        long steps = 0;
        long guestNanos = 0;

        while (true) {
            if (steps >= quantum.maxInstructions()) {
                return report(ExecutionStatus.PREEMPTED, steps, guestNanos);
            }
            if (guestNanos >= quantum.maxGuestNanos()) {
                return report(ExecutionStatus.PREEMPTED, steps, guestNanos);
            }

            Step step;
            if (cpu.halted()) {
                poll.run();
                step = executeStep(quantum);
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
                    step = executeStep(quantum);
                    if (!step.attempted()) {
                        return report(ExecutionStatus.HALTED, steps, guestNanos);
                    }
                }
            } else {
                step = executeStep(quantum);
                if (!step.attempted()) {
                    continue;
                }
            }

            if (step.watchdog()) {
                return report(ExecutionStatus.WATCHDOG, steps + 1, guestNanos);
            }

            steps++;
            guestNanos = saturatingAdd(guestNanos, step.guestNanos());
            clock.advanceNanos(NANOS_PER_INSTRUCTION);

            if (steps >= quantum.maxInstructions()) {
                return report(ExecutionStatus.PREEMPTED, steps, guestNanos);
            }
            if (guestNanos >= quantum.maxGuestNanos()) {
                return report(ExecutionStatus.PREEMPTED, steps, guestNanos);
            }
        }
    }

    private Step executeStep(ExecutionQuantum quantum) {
        long start = nanoClock.nanoTime();
        boolean attempted = cpu.step();
        if (!attempted) {
            return Step.NOT_ATTEMPTED;
        }
        long elapsed = Math.max(0L, nanoClock.nanoTime() - start);
        if (cpu.lastOpcode() == Opcode.HOST) {
            return new Step(true, 0L, false);
        }
        return new Step(true, elapsed, watchdog.enabled() && elapsed > quantum.maxGuestNanos());
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

    private record Step(boolean attempted, long guestNanos, boolean watchdog) {

        private static final Step NOT_ATTEMPTED = new Step(false, 0L, false);
    }
}

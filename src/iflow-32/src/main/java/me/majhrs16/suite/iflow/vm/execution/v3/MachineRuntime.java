package me.majhrs16.suite.iflow.vm.execution.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

import me.majhrs16.suite.iflow.vm.isa.v3.Cpu;
import me.majhrs16.suite.iflow.vm.isa.v3.HostHandler;
import me.majhrs16.suite.iflow.vm.isa.v3.VirtualClock;
import me.majhrs16.suite.iflow.vm.memory.v3.CoreMemory;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineMemory;

/**
 * Host-side vCPU set of one v3 VM.
 *
 * <p>Creates one {@link Cpu}, one {@link CoreMemory} view and one {@link ExecutionEngine} per
 * configured core. All cores start at the same reset vector; they share physical RAM, MMIO and the
 * global clock, but each has its own CSR file, TLB and retired-instruction counter. The host does
 * not schedule guest processes and does not choose a core for a message: the caller selects the
 * core to run and the guest kernel owns the process scheduler.</p>
 *
 * <p>A watchdog step fault marks the observed core and the whole VM as {@code FAULTED}. A hung
 * step may have mutated shared state, so the host stops instead of continuing with another core or
 * presenting a false success.</p>
 */
public final class MachineRuntime {

    private final MachineMemory memory;
    private final VirtualClock clock;
    private final ExecutionQuantum quantum;
    private final List<Core> cores;
    private boolean faulted;

    public MachineRuntime(MachineMemory memory,
                          VirtualClock clock,
                          int resetVector,
                          ExecutionQuantum quantum,
                          WatchdogConfig watchdog) {
        this(memory, clock, resetVector, quantum, watchdog, ignored -> null, System::nanoTime);
    }

    public MachineRuntime(MachineMemory memory,
                          VirtualClock clock,
                          int resetVector,
                          ExecutionQuantum quantum,
                          WatchdogConfig watchdog,
                          IntFunction<HostHandler> hostHandlers,
                          NanoClock nanoClock) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.quantum = Objects.requireNonNull(quantum, "quantum");
        Objects.requireNonNull(watchdog, "watchdog");
        Objects.requireNonNull(hostHandlers, "hostHandlers");
        Objects.requireNonNull(nanoClock, "nanoClock");

        List<Core> built = new ArrayList<>(memory.coreCount());
        for (int coreId = 0; coreId < memory.coreCount(); coreId++) {
            CoreMemory coreMemory = memory.core(coreId);
            Cpu cpu = new Cpu(resetVector, coreMemory, coreMemory, clock,
                    hostHandlers.apply(coreId), coreMemory, coreMemory.csrs());
            ExecutionEngine engine = new ExecutionEngine(cpu, clock, nanoClock,
                    memory.timer()::nextDeadline, coreMemory::poll, watchdog);
            built.add(new Core(coreId, coreMemory, cpu, engine));
        }
        this.cores = List.copyOf(built);
    }

    public int coreCount() {
        return cores.size();
    }

    public MachineMemory memory() {
        return memory;
    }

    public VirtualClock clock() {
        return clock;
    }

    public ExecutionQuantum quantum() {
        return quantum;
    }

    public boolean faulted() {
        return faulted;
    }

    public Core core(int coreId) {
        if (coreId < 0 || coreId >= cores.size()) {
            throw new IllegalArgumentException(
                    "COREID out of range: " + coreId + " (core-count " + cores.size() + ")");
        }
        return cores.get(coreId);
    }

    /**
     * Runs one slice of the selected core. The caller is the guest scheduler: this method never
     * picks another core, steals work or changes the message owner.
     */
    public ExecutionReport runCore(int coreId) {
        return runCore(coreId, quantum);
    }

    public ExecutionReport runCore(int coreId, ExecutionQuantum slice) {
        Objects.requireNonNull(slice, "slice");
        if (faulted) {
            throw new IllegalStateException("VM is FAULTED; restart is required");
        }
        Core core = core(coreId);
        if (core.faulted) {
            throw new IllegalStateException("COREID " + coreId + " is FAULTED");
        }
        ExecutionReport report = core.engine.run(slice);
        if (report.status() == ExecutionStatus.WATCHDOG) {
            core.faulted = true;
            faulted = true;
        }
        return report;
    }

    /** Architectural view and host execution state of one core. */
    public static final class Core {

        private final int id;
        private final CoreMemory memory;
        private final Cpu cpu;
        private final ExecutionEngine engine;
        private boolean faulted;

        private Core(int id, CoreMemory memory, Cpu cpu, ExecutionEngine engine) {
            this.id = id;
            this.memory = memory;
            this.cpu = cpu;
            this.engine = engine;
        }

        public int id() {
            return id;
        }

        public CoreMemory memory() {
            return memory;
        }

        public Cpu cpu() {
            return cpu;
        }

        public boolean faulted() {
            return faulted;
        }
    }
}

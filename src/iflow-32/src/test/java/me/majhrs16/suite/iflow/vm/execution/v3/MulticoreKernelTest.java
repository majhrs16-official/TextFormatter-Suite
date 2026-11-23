package me.majhrs16.suite.iflow.vm.execution.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.VirtualClock;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineFixture;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineMemory;
import me.majhrs16.suite.iflow.vm.rom.RomImage;
import me.majhrs16.suite.iflow.vm.rom.RomParser;

/**
 * Boots two vCPUs of the same v3 VM and exercises the guest multicore kernel.
 *
 * <p>The fixture covers the contract of ADR-037 §6.1 that V3-2b still owes: all
 * cores start at {@code resetVector}, read {@code COREID} and wait for the
 * kernel; the secondary core populates its TLB with a page, core 0 changes the
 * PTE and sends a broadcast IPI on line 16, the handler executes
 * {@code SFENCE.VMA} and acknowledges its PIC bank, and the secondary reads the
 * new frame. Finally every core claims tasks from one shared queue with
 * {@code AMOADD.W} and halts.</p>
 *
 * <p>The host only runs one core per slice and does not schedule guest work. A
 * small quantum forces the test to interleave the cores; it is not a
 * replacement for the guest scheduler.</p>
 */
class MulticoreKernelTest {

    private static final int SCRATCH = 0x0200_E000;
    private static final int BOOT_MARKER = SCRATCH + 0x30;
    private static final int TRAP_MARKER = SCRATCH + 0x34;
    private static final int BOOT_READY = SCRATCH + 0x40;
    private static final int KERNEL_READY = SCRATCH + 0x44;
    private static final int SCHED_READY = SCRATCH + 0x48;
    private static final int TLB_ONLINE = SCRATCH + 0x4C;
    private static final int TLB_ACK = SCRATCH + 0x50;
    private static final int TLB_BEFORE = SCRATCH + 0x54;
    private static final int TLB_AFTER = SCRATCH + 0x58;
    private static final int SCHED_START = SCRATCH + 0x5C;
    private static final int TASKS_DONE = SCRATCH + 0x60;
    private static final int SCHEDULER_DONE = SCRATCH + 0x64;
    private static final int SECONDARIES = SCRATCH + 0x68;
    private static final int NEXT_TASK = SCRATCH + 0x80;
    private static final int TASKS_BY_CORE = SCRATCH + 0xC0;
    private static final int TASK_RESULTS = SCRATCH + 0x100;
    private static final int TLB_FRAMES = SCRATCH + 0x300;
    private static final int MAX_ROUNDS = 20_000;

    private static final int BOOT_MARKER_VALUE = 0x600D_0001;
    private static final int FRAME_A_VALUE = 0xA000_0001;
    private static final int FRAME_B_VALUE = 0xB000_0002;
    private static final int TASK_COUNT = 8;

    @Test
    void bootsTwoCoresShootsDownTlbAndRunsScheduler() {
        RomImage image = RomParser.parse(fixture());
        VirtualClock clock = new VirtualClock();
        MachineMemory memory = MachineMemory.fromRom(
                new CsrFile(0), image, MachineFixture.RAM_SIZE, clock, false, 2);
        MachineRuntime runtime = new MachineRuntime(
                memory,
                clock,
                image.header().resetVector(),
                new ExecutionQuantum(200, 50_000_000L),
                new WatchdogConfig(false));

        runUntilAllCoresHalt(runtime);

        assertEquals(BOOT_MARKER_VALUE, word(memory, BOOT_MARKER));
        assertEquals(1, word(memory, BOOT_READY));
        assertEquals(1, word(memory, KERNEL_READY));
        assertEquals(1, word(memory, SCHED_READY));
        assertEquals(1, word(memory, SECONDARIES));
        assertEquals(1, word(memory, TLB_ONLINE));
        assertEquals(1, word(memory, TLB_ACK));
        assertEquals(1, word(memory, SCHED_START));
        assertEquals(0, word(memory, TRAP_MARKER));

        // Core 1 saw frame A before the shootdown and frame B afterwards.
        assertEquals(FRAME_A_VALUE, word(memory, TLB_BEFORE));
        assertEquals(FRAME_B_VALUE, word(memory, TLB_AFTER));
        assertEquals(FRAME_A_VALUE, word(memory, TLB_FRAMES + 8));
        assertEquals(FRAME_B_VALUE, word(memory, TLB_FRAMES + 12));

        // The shared queue delivered every task exactly once and both cores
        // claimed work. AMOADD.W is the only source of task indices, so the
        // counter may run past TASK_COUNT when a core probes the empty queue.
        assertTrue(word(memory, NEXT_TASK) >= TASK_COUNT,
                "queue did not drain: " + word(memory, NEXT_TASK));
        assertEquals(TASK_COUNT, word(memory, TASKS_DONE));
        assertEquals(1, word(memory, SCHEDULER_DONE));
        assertEquals(TASK_COUNT,
                word(memory, TASKS_BY_CORE) + word(memory, TASKS_BY_CORE + 4));
        assertTrue(word(memory, TASKS_BY_CORE) > 0, "core 0 claimed no task");
        assertTrue(word(memory, TASKS_BY_CORE + 4) > 0, "core 1 claimed no task");

        for (int index = 0; index < TASK_COUNT; index++) {
            int marker = word(memory, TASK_RESULTS + index * Integer.BYTES);
            int core = marker >>> 16;
            int task = marker & 0xFFFF;
            assertEquals(index, task, "task slot " + index + " was claimed twice");
            assertTrue(core == 0 || core == 1, "unexpected core in task marker: " + core);
        }
    }

    private static void runUntilAllCoresHalt(MachineRuntime runtime) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean scheduler = word(runtime.memory(), SCHED_START) != 0;
            ExecutionQuantum slice = scheduler
                    ? new ExecutionQuantum(1, 50_000_000L)
                    : runtime.quantum();
            for (int coreId = 0; coreId < runtime.coreCount(); coreId++) {
                ExecutionReport report = runtime.runCore(coreId, slice);
                if (report.status() == ExecutionStatus.WATCHDOG) {
                    fail("core " + coreId + " hit the watchdog");
                }
            }
            // A halted core may be waiting for an IPI, so it is retried every
            // round; the run ends only when the scheduler finished and every
            // core halted.
            boolean allHalted = true;
            for (int coreId = 0; coreId < runtime.coreCount(); coreId++) {
                allHalted &= runtime.core(coreId).cpu().halted();
            }
            if (allHalted && word(runtime.memory(), SCHEDULER_DONE) != 0) {
                return;
            }
        }
        fail("cores did not halt within " + MAX_ROUNDS + " rounds");
    }

    private static int word(MachineMemory memory, int address) {
        return memory.physical().readWord(address);
    }

    private static byte[] fixture() {
        try (InputStream input = MulticoreKernelTest.class
                .getResourceAsStream("/rom/v3/multicore.rom")) {
            assert input != null : "multicore fixture is missing";
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read multicore fixture", exception);
        }
    }
}

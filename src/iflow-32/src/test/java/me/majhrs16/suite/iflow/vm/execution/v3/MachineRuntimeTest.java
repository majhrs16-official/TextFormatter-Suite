package me.majhrs16.suite.iflow.vm.execution.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.Opcode;
import me.majhrs16.suite.iflow.vm.isa.v3.VirtualClock;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineFixture;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineMemory;
import me.majhrs16.suite.iflow.vm.memory.v3.Nvram;

class MachineRuntimeTest {

    private static final int RESET = MachineFixture.PTBR;

    @Test
    void createsOneCpuPerConfiguredCoreAtTheSameResetVector() {
        VirtualClock clock = new VirtualClock();
        MachineRuntime runtime = runtime(clock, 3, WatchdogConfig.defaults(), System::nanoTime);

        assertEquals(3, runtime.coreCount());
        for (int coreId = 0; coreId < 3; coreId++) {
            MachineRuntime.Core core = runtime.core(coreId);
            assertEquals(coreId, core.id());
            assertEquals(coreId, core.cpu().csrs().coreId());
            assertEquals(RESET, core.cpu().pc());
            assertEquals(0, core.cpu().cycles());
            assertFalse(core.faulted());
        }
        assertNotSame(runtime.core(0).cpu().csrs(), runtime.core(1).cpu().csrs());
        assertNotSame(runtime.core(0).memory().mmu(), runtime.core(1).memory().mmu());
    }

    @Test
    void runsOnlyTheSelectedCore() {
        VirtualClock clock = new VirtualClock();
        MachineRuntime runtime = runtime(clock, 2, WatchdogConfig.defaults(), System::nanoTime);
        writeNops(runtime.memory(), RESET, 3);

        ExecutionReport first = runtime.runCore(0, new ExecutionQuantum(2, 1_000_000));

        assertEquals(ExecutionStatus.PREEMPTED, first.status());
        assertEquals(2, runtime.core(0).cpu().cycles());
        assertEquals(0, runtime.core(1).cpu().cycles());
        assertEquals(RESET, runtime.core(1).cpu().pc());

        ExecutionReport second = runtime.runCore(1, new ExecutionQuantum(3, 1_000_000));

        assertEquals(ExecutionStatus.PREEMPTED, second.status());
        assertEquals(2, runtime.core(0).cpu().cycles());
        assertEquals(3, runtime.core(1).cpu().cycles());
        assertEquals(RESET + 9, runtime.core(1).cpu().pc());
    }

    @Test
    void watchdogFaultsTheObservedCoreAndStopsTheMachine() {
        VirtualClock clock = new VirtualClock();
        AtomicLong wall = new AtomicLong();
        NanoClock slow = () -> wall.getAndAdd(60_000_000L);
        MachineRuntime runtime = runtime(clock, 2, WatchdogConfig.defaults(), slow);
        writeNops(runtime.memory(), RESET, 2);

        ExecutionReport report = runtime.runCore(1, new ExecutionQuantum(10, 50_000_000));

        assertEquals(ExecutionStatus.WATCHDOG, report.status());
        assertTrue(runtime.faulted());
        assertTrue(runtime.core(1).faulted());
        assertFalse(runtime.core(0).faulted());
        assertThrows(IllegalStateException.class,
                () -> runtime.runCore(0, new ExecutionQuantum(10, 50_000_000)));
    }

    @Test
    void rejectsCoresOutsideTheConfiguredCount() {
        MachineRuntime runtime = runtime(new VirtualClock(), 2, WatchdogConfig.defaults(),
                System::nanoTime);

        assertThrows(IllegalArgumentException.class, () -> runtime.core(-1));
        assertThrows(IllegalArgumentException.class, () -> runtime.core(2));
    }

    private static MachineRuntime runtime(VirtualClock clock, int coreCount,
                                          WatchdogConfig watchdog, NanoClock nanoClock) {
        MachineMemory memory = new MachineMemory(new CsrFile(0), new byte[0],
                MachineFixture.RAM_SIZE, clock,
                new Nvram(new byte[Nvram.BYTES]), 80, 0,
                MachineMemory.ABI_VERSION, MachineMemory.DEFAULT_FEATURES, coreCount);
        return new MachineRuntime(memory, clock, RESET, ExecutionQuantum.defaults(),
                watchdog, ignored -> null, nanoClock);
    }

    private static void writeNops(MachineMemory memory, int address, int count) {
        for (int index = 0; index < count; index++) {
            writeOpcode(memory, address + index * 3, Opcode.NOP);
        }
    }

    private static void writeOpcode(MachineMemory memory, int address, Opcode opcode) {
        byte[] bytes = new byte[3];
        bytes[0] = (byte) ((opcode.family() << 6) | (opcode.operation() << 2));
        for (int index = 0; index < bytes.length; index++) {
            memory.physical().writeByte(address + index, bytes[index] & 0xFF);
        }
    }
}

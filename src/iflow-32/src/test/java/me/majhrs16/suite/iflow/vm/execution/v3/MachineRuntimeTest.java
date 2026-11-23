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
import me.majhrs16.suite.iflow.vm.isa.v3.Status;
import me.majhrs16.suite.iflow.vm.isa.v3.VirtualClock;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineFixture;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineMemory;
import me.majhrs16.suite.iflow.vm.memory.v3.Nvram;
import me.majhrs16.suite.iflow.vm.memory.v3.PhysicalMemory;

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
        MachineRuntime runtime = runtime(clock, 2, WatchdogConfig.defaults(), () -> 0L);
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

    @Test
    void snapshotsOnlyHaltedCoresAndSharesFramesCopyOnWrite() {
        MachineRuntime runtime = runtime(new VirtualClock(), 1, WatchdogConfig.defaults(),
                System::nanoTime);
        writeNops(runtime.memory(), RESET, 2);
        runtime.runCore(0, new ExecutionQuantum(1, 1_000_000));

        assertThrows(IllegalStateException.class, runtime::snapshotMemory);

        halt(runtime, 0);
        int address = PhysicalMemory.RAM_BASE;
        runtime.memory().physical().writeByte(address, 0x11);
        PhysicalMemory snapshot = runtime.snapshotMemory();

        assertEquals(0x11, snapshot.readByte(address));
        runtime.memory().physical().writeByte(address, 0x22);
        assertEquals(0x11, snapshot.readByte(address));
        snapshot.writeByte(address, 0x33);
        assertEquals(0x22, runtime.memory().physical().readByte(address));
    }

    @Test
    void powerOffKeepsCommittedNvramAndRejectsExecution() {
        Nvram nvram = new Nvram(new byte[Nvram.BYTES]);
        MachineRuntime runtime = runtime(new VirtualClock(), 1, WatchdogConfig.defaults(),
                System::nanoTime, nvram);
        nvram.writeRegister(Nvram.ADDR, 0);
        nvram.writeRegister(Nvram.DATA, 0x11);
        nvram.commit();
        nvram.writeRegister(Nvram.DATA, 0x22);
        runtime.memory().physical().writeByte(PhysicalMemory.RAM_BASE, 0x44);
        halt(runtime, 0);

        byte[] persistent = runtime.powerOff();

        assertEquals(0x11, persistent[0] & 0xFF);
        assertTrue(runtime.stopped());
        assertThrows(IllegalStateException.class, () -> runtime.runCore(0));
    }

    @Test
    void coldResetClearsRuntimeStateAndKeepsCommittedNvram() {
        Nvram nvram = new Nvram(new byte[Nvram.BYTES]);
        MachineRuntime runtime = runtime(new VirtualClock(), 1, WatchdogConfig.defaults(),
                System::nanoTime, nvram);
        nvram.writeRegister(Nvram.ADDR, 0);
        nvram.writeRegister(Nvram.DATA, 0x11);
        nvram.commit();
        runtime.memory().physical().writeByte(PhysicalMemory.RAM_BASE, 0x44);
        halt(runtime, 0);
        runtime.powerOff();

        runtime.coldReset();

        assertFalse(runtime.stopped());
        assertEquals(RESET, runtime.core(0).cpu().pc());
        assertEquals(0, runtime.core(0).cpu().cycles());
        assertEquals(0, runtime.memory().physical().readByte(PhysicalMemory.RAM_BASE));
        assertEquals(0x11, runtime.memory().nvram().persistentBytes()[0] & 0xFF);

        writeNops(runtime.memory(), RESET, 1);
        assertEquals(ExecutionStatus.PREEMPTED,
                runtime.runCore(0, new ExecutionQuantum(1, 1_000_000)).status());
    }

    @Test
    void keepsMachineStateAcrossSlicesUntilPowerOff() {
        MachineRuntime runtime = runtime(new VirtualClock(), 1, WatchdogConfig.defaults(),
                () -> 0L);
        int address = PhysicalMemory.RAM_BASE + 0x100;
        runtime.memory().physical().writeByte(address, 0x5A);
        writeNops(runtime.memory(), RESET, 1);
        runtime.runCore(0, new ExecutionQuantum(1, 1_000_000));
        int pc = runtime.core(0).cpu().pc();

        writeNops(runtime.memory(), pc, 1);
        runtime.runCore(0, new ExecutionQuantum(1, 1_000_000));

        assertEquals(pc + 3, runtime.core(0).cpu().pc());
        assertEquals(2, runtime.core(0).cpu().cycles());
        assertEquals(0x5A, runtime.memory().physical().readByte(address));
        assertTrue(runtime.clock().timeNanos() > 0);
        assertFalse(runtime.stopped());
    }

    private static void halt(MachineRuntime runtime, int coreId) {
        var cpu = runtime.core(coreId).cpu();
        writeOpcode(runtime.memory(), cpu.pc(), Opcode.HALT);
        cpu.csrs().setStatus(Status.fromBits(Status.PRV | Status.IE));
        assertEquals(ExecutionStatus.HALTED, runtime.runCore(coreId).status());
    }

    private static MachineRuntime runtime(VirtualClock clock, int coreCount,
                                          WatchdogConfig watchdog, NanoClock nanoClock) {
        return runtime(clock, coreCount, watchdog, nanoClock, new Nvram(new byte[Nvram.BYTES]));
    }

    private static MachineRuntime runtime(VirtualClock clock, int coreCount,
                                          WatchdogConfig watchdog, NanoClock nanoClock,
                                          Nvram nvram) {
        MachineMemory memory = new MachineMemory(new CsrFile(0), new byte[0],
                MachineFixture.RAM_SIZE, clock,
                nvram, 80, 0,
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

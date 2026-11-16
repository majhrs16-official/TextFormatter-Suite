package me.majhrs16.suite.iflow.vm.execution.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import me.majhrs16.suite.iflow.vm.isa.v3.Cpu;
import me.majhrs16.suite.iflow.vm.isa.v3.Csr;
import me.majhrs16.suite.iflow.vm.isa.v3.HostHandler;
import me.majhrs16.suite.iflow.vm.isa.v3.HostResult;
import me.majhrs16.suite.iflow.vm.isa.v3.HostStatus;
import me.majhrs16.suite.iflow.vm.isa.v3.Opcode;
import me.majhrs16.suite.iflow.vm.isa.v3.Status;
import me.majhrs16.suite.iflow.vm.isa.v3.VirtualClock;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineFixture;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineMemory;
import me.majhrs16.suite.iflow.vm.memory.v3.Timer;

class ExecutionEngineTest {

    private static final int RESET = 0x0200_0000;
    private static final int TVEC = 0x0201_0000;

    @Test
    void preemptsAtAnInstructionBoundaryAndKeepsTheCpuResumable() {
        VirtualClock clock = new VirtualClock(100);
        MachineFixture fixture = new MachineFixture(clock);
        Cpu cpu = cpu(fixture, clock, null);
        fixture.csrs.setStatus(Status.fromBits(Status.PRV | Status.IE));
        for (int index = 0; index < 3; index++) {
            writeOpcode(fixture.memory, RESET + index * 3, Opcode.NOP);
        }
        writeOpcode(fixture.memory, RESET + 9, Opcode.HALT);
        ExecutionEngine engine = new ExecutionEngine(cpu, clock,
                () -> Long.MAX_VALUE, () -> { });

        ExecutionReport first = engine.run(new ExecutionQuantum(3, 50_000_000));

        assertEquals(ExecutionStatus.PREEMPTED, first.status());
        assertEquals(3, first.steps());
        assertEquals(3, first.cycles());
        assertEquals(103, first.virtualNanos());
        assertEquals(RESET + 9, cpu.pc());

        ExecutionReport second = engine.run(new ExecutionQuantum(10, 50_000_000));

        assertEquals(ExecutionStatus.HALTED, second.status());
        assertEquals(1, second.steps());
        assertEquals(4, second.cycles());
        assertTrue(cpu.halted());
    }

    @Test
    void preemptsWhenTheWallClockSliceExpiresWithoutFailingTheGuest() {
        VirtualClock clock = new VirtualClock();
        MachineFixture fixture = new MachineFixture(clock);
        AtomicLong wall = new AtomicLong();
        NanoClock nanoClock = () -> wall.getAndAdd(20_000_000);
        Cpu cpu = cpu(fixture, clock, null);
        fixture.csrs.setStatus(Status.fromBits(Status.PRV));
        for (int index = 0; index < 4; index++) {
            writeOpcode(fixture.memory, RESET + index * 3, Opcode.NOP);
        }
        ExecutionEngine engine = new ExecutionEngine(cpu, clock, nanoClock,
                () -> Long.MAX_VALUE, () -> { });

        ExecutionReport report = engine.run(new ExecutionQuantum(100, 50_000_000));

        assertEquals(ExecutionStatus.PREEMPTED, report.status());
        assertEquals(3, report.steps());
        assertEquals(60_000_000, report.guestNanos());
        assertEquals(3, report.cycles());
    }

    @Test
    void watchdogFaultsWhenOneGuestStepExceedsTheBound() {
        VirtualClock clock = new VirtualClock();
        MachineFixture fixture = new MachineFixture(clock);
        AtomicLong wall = new AtomicLong();
        NanoClock nanoClock = () -> wall.getAndAdd(60_000_000);
        Cpu cpu = cpu(fixture, clock, null);
        fixture.csrs.setStatus(Status.fromBits(Status.PRV));
        writeOpcode(fixture.memory, RESET, Opcode.NOP);
        ExecutionEngine engine = new ExecutionEngine(cpu, clock, nanoClock,
                () -> Long.MAX_VALUE, () -> { });

        ExecutionReport report = engine.run(new ExecutionQuantum(100, 50_000_000));

        assertEquals(ExecutionStatus.WATCHDOG, report.status());
        assertEquals(1, report.steps());
        assertEquals(1, report.cycles());
        assertEquals(0, report.guestNanos());
        assertEquals(RESET + 3, cpu.pc());
    }

    @Test
    void haltedCpuWithoutAWakeSourceReturnsHalted() {
        VirtualClock clock = new VirtualClock(10);
        MachineFixture fixture = new MachineFixture(clock);
        Cpu cpu = cpu(fixture, clock, null);
        fixture.csrs.setStatus(Status.fromBits(Status.PRV | Status.IE));
        writeOpcode(fixture.memory, RESET, Opcode.HALT);
        ExecutionEngine engine = new ExecutionEngine(cpu, clock,
                fixture.memory.timer()::nextDeadline, fixture.memory.timer()::tick);

        ExecutionReport report = engine.run(new ExecutionQuantum(5, 50_000_000));

        assertEquals(ExecutionStatus.HALTED, report.status());
        assertEquals(1, report.steps());
        assertEquals(1, report.cycles());
        assertEquals(11, report.virtualNanos());
        assertTrue(cpu.halted());
    }

    @Test
    void sleepAdvancesVirtualTimeAndWakesOnTheTimerIrq() {
        VirtualClock clock = new VirtualClock(1_000);
        MachineFixture fixture = new MachineFixture(clock);
        Cpu cpu = cpu(fixture, clock, null);
        fixture.csrs.write(Csr.TVEC, TVEC, 0);
        fixture.csrs.setStatus(Status.fromBits(Status.PRV | Status.IE));
        writeOpcode(fixture.memory, RESET, Opcode.HALT);
        writeOpcode(fixture.memory, RESET + 3, Opcode.NOP);
        writeOpcode(fixture.memory, RESET + 6, Opcode.HALT);
        writeOpcode(fixture.memory, TVEC + 4 * 16, Opcode.ERET);

        Timer timer = fixture.memory.timer();
        long deadline = clock.timeNanos() + 100;
        timer.writeRegister(Timer.CMP_LO, (int) deadline);
        timer.writeRegister(Timer.CMP_HI, (int) (deadline >>> 32));
        timer.writeRegister(Timer.CTRL, Timer.CTRL_ENABLE | Timer.CTRL_IRQ);
        fixture.memory.pic().setMask(0);

        ExecutionEngine engine = new ExecutionEngine(cpu, clock,
                timer::nextDeadline, timer::tick);
        ExecutionReport report = engine.run(new ExecutionQuantum(20, 50_000_000));

        assertEquals(ExecutionStatus.HALTED, report.status());
        assertEquals(4, report.cycles());
        assertEquals(RESET + 9, cpu.pc());
        assertTrue(cpu.halted());
        assertFalse(report.virtualNanos() < deadline);
        assertEquals(Timer.STATUS_FIRED, timer.readRegister(Timer.STATUS));
    }

    @Test
    void hostTimeIsNotChargedToTheGuestQuantumOrWatchdog() {
        VirtualClock clock = new VirtualClock();
        MachineFixture fixture = new MachineFixture(clock);
        AtomicLong wall = new AtomicLong();
        NanoClock nanoClock = () -> wall.getAndAdd(5);
        HostHandler handler = call -> {
            wall.addAndGet(1_000);
            return new HostResult(HostStatus.OK, 0);
        };
        Cpu cpu = cpu(fixture, clock, handler);
        fixture.csrs.setStatus(Status.fromBits(Status.PRV | Status.IE));
        writeOpcode(fixture.memory, RESET, Opcode.HOST);
        writeOpcode(fixture.memory, RESET + 3, Opcode.HALT);
        ExecutionEngine engine = new ExecutionEngine(cpu, clock, nanoClock,
                () -> Long.MAX_VALUE, () -> { });

        ExecutionReport report = engine.run(new ExecutionQuantum(10, 10));

        assertEquals(ExecutionStatus.HALTED, report.status());
        assertEquals(2, report.steps());
        assertEquals(2, report.cycles());
        assertEquals(5, report.guestNanos());
    }

    private static Cpu cpu(MachineFixture fixture, VirtualClock clock, HostHandler handler) {
        return new Cpu(RESET, fixture.memory, fixture.memory, clock, handler,
                fixture.memory, fixture.csrs);
    }

    private static void writeOpcode(MachineMemory memory, int address, Opcode opcode) {
        byte[] bytes = new byte[3];
        bytes[0] = (byte) ((opcode.family() << 6) | (opcode.operation() << 2));
        for (int index = 0; index < bytes.length; index++) {
            memory.physical().writeByte(address + index, bytes[index] & 0xFF);
        }
    }
}

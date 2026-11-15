package me.majhrs16.suite.iflow.vm.isa.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import me.majhrs16.suite.iflow.vm.memory.v3.MachineFixture;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineMemory;
import me.majhrs16.suite.iflow.vm.memory.v3.Mmu;
import me.majhrs16.suite.iflow.vm.memory.v3.Timer;

class CpuInterruptTest {

    private static final int RESET = 0x0200_0000;
    private static final int TVEC = 0x0201_0000;

    @Test
    void interruptIsDeliveredBeforeFetchAndEretReturns() {
        MachineFixture fixture = new MachineFixture();
        Cpu cpu = new Cpu(RESET, fixture.memory, fixture.memory,
                fixture.clock::get, null, fixture.memory, fixture.csrs);
        fixture.csrs.write(Csr.TVEC, TVEC, 0);
        fixture.csrs.setStatus(Status.fromBits(Status.PRV | Status.IE));
        writeCode(fixture.memory, RESET, Opcode.NOP);
        writeCode(fixture.memory, TVEC + 4 * 17, Opcode.ERET);
        fixture.memory.pic().setMask(0xFFFF_FFFD);
        fixture.memory.pic().raise(1);

        assertTrue(cpu.step());

        assertEquals(TVEC + 4 * 17, cpu.pc());
        assertEquals(0, cpu.cycles());
        assertEquals(RESET, cpu.csrs().epc());
        assertEquals(17, cpu.csrs().cause());
        assertTrue(cpu.csrs().status().trap());
        assertFalse(cpu.csrs().status().interruptEnable());
        assertTrue(cpu.csrs().status().previousInterruptEnable());
        assertTrue(cpu.csrs().status().supervisor());
        assertTrue(cpu.csrs().status().previousSupervisor());

        assertTrue(cpu.step());

        assertEquals(RESET, cpu.pc());
        assertEquals(1, cpu.cycles());
        assertFalse(cpu.csrs().status().trap());
        assertTrue(cpu.csrs().status().interruptEnable());
    }

    @Test
    void haltedCpuWakesOnInterruptWithoutRetiringAnInstruction() {
        MachineFixture fixture = new MachineFixture();
        Cpu cpu = new Cpu(RESET, fixture.memory, fixture.memory,
                fixture.clock::get, null, fixture.memory, fixture.csrs);
        fixture.csrs.write(Csr.TVEC, TVEC, 0);
        fixture.csrs.setStatus(Status.fromBits(Status.PRV | Status.IE));
        writeCode(fixture.memory, RESET, Opcode.HALT);

        assertTrue(cpu.step());
        assertTrue(cpu.halted());
        assertEquals(1, cpu.cycles());

        fixture.memory.pic().setMask(0xFFFF_FFFB);
        fixture.memory.pic().raise(2);
        assertTrue(cpu.step());

        assertFalse(cpu.halted());
        assertEquals(TVEC + 4 * 18, cpu.pc());
        assertEquals(RESET + 3, cpu.csrs().epc());
        assertEquals(1, cpu.cycles());
    }

    @Test
    void maskedInterruptWaitsUntilInterruptsAreEnabled() {
        MachineFixture fixture = new MachineFixture();
        Cpu cpu = new Cpu(RESET, fixture.memory, fixture.memory,
                fixture.clock::get, null, fixture.memory, fixture.csrs);
        fixture.csrs.write(Csr.TVEC, TVEC, 0);
        writeCode(fixture.memory, RESET, Opcode.NOP);
        fixture.memory.pic().setMask(0xFFFF_FFFE);
        fixture.memory.pic().raise(0);

        assertTrue(cpu.step());
        assertEquals(RESET + 3, cpu.pc());
        assertEquals(1, cpu.cycles());
        assertEquals(1, fixture.memory.pic().pending());

        fixture.csrs.setStatus(Status.fromBits(Status.PRV | Status.IE));
        assertTrue(cpu.step());

        assertEquals(TVEC + 4 * 16, cpu.pc());
        assertEquals(1, cpu.cycles());
        assertEquals(RESET + 3, cpu.csrs().epc());
    }

    @Test
    void timerAdvancesEvenWhenInterruptsAreDisabled() {
        MachineFixture fixture = new MachineFixture();
        Cpu cpu = new Cpu(RESET, fixture.memory, fixture.memory,
                fixture.clock::get, null, fixture.memory, fixture.csrs);
        long now = fixture.clock.get();
        fixture.csrs.setStatus(Status.fromBits(Status.PRV));
        writeCode(fixture.memory, RESET, Opcode.NOP);
        fixture.memory.timer().writeRegister(Timer.CMP_LO, (int) (now + 1));
        fixture.memory.timer().writeRegister(Timer.CMP_HI, 0);
        fixture.memory.timer().writeRegister(Timer.CTRL,
                Timer.CTRL_ENABLE | Timer.CTRL_IRQ);
        fixture.clock.set(now + 1);

        assertTrue(cpu.step());

        assertEquals(Timer.STATUS_FIRED,
                fixture.memory.timer().readRegister(Timer.STATUS));
        assertEquals(1, fixture.memory.pic().pending() & 1);
        assertEquals(RESET + 3, cpu.pc());
        assertFalse(cpu.csrs().status().trap());
    }

    @Test
    void fetchFaultAcrossAnUnmappedPageKeepsInstructionPcAndBadAddress() {
        MachineFixture fixture = new MachineFixture();
        int target = 0x4000_0000;
        byte[] instruction = InstructionFixture.instruction(1, 0, 1, Register.R1, 0, 0, 0, 0, 1);
        fixture.loadPhysical(MachineFixture.FRAME_A + 0xFFE,
                instruction[0] & 0xFF,
                instruction[1] & 0xFF,
                instruction[2] & 0xFF,
                instruction[3] & 0xFF,
                instruction[4] & 0xFF,
                instruction[5] & 0xFF,
                instruction[6] & 0xFF);
        Cpu cpu = new Cpu(target + 0xFFE, fixture.memory, fixture.memory,
                fixture.clock::get, null, fixture.memory, fixture.csrs);
        fixture.enableMmu(true);
        fixture.mapPage(target, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_X);
        fixture.csrs.write(Csr.TVEC, TVEC, 0);

        assertTrue(cpu.step());

        assertEquals(TVEC + 4 * MachineFault.Code.INSTRUCTION_FAULT.vector(), cpu.pc());
        assertEquals(target + 0xFFE, cpu.csrs().epc());
        assertEquals(target + 0x1000, cpu.csrs().badvaddr());
        assertEquals(0, cpu.cycles());
    }

    private static void writeCode(MachineMemory memory, int address, Opcode opcode) {
        byte[] bytes = InstructionFixture.instruction(opcode.family(), opcode.operation(),
                0, 0, 0, 0, 0, 0, 0);
        for (int index = 0; index < bytes.length; index++) {
            memory.physical().writeByte(address + index, bytes[index] & 0xFF);
        }
    }
}

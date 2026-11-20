package me.majhrs16.suite.iflow.vm.isa.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CpuTest {

    private static final int TVEC = 0x300;

    private TestMemory memory;
    private Cpu cpu;
    private long timeNanos;

    @BeforeEach
    void setUp() {
        memory = new TestMemory();
        timeNanos = 0x1_0000_0002L;
        cpu = new Cpu(0, memory, memory, () -> timeNanos);
    }

    @Test
    void movImmediateWritesRegisterAndAdvances() {
        load(0, Opcode.MOV, InstructionForm.IMMEDIATE, Register.R1, 0, 0x1234_5678);

        step();

        assertEquals(0x1234_5678, cpu.registers().get(Register.R1));
        assertEquals(7, cpu.pc());
        assertEquals(1, cpu.cycles());
    }

    @Test
    void movRegisterZeroExtendsByteAndHalf() {
        cpu.registers().set(Register.R1, 0x1234_5678);
        load(0, Opcode.MOV, InstructionForm.REGISTER, Register.R2, Register.R1,
                0, false, MemorySize.BYTE, 0);

        step();

        assertEquals(0x78, cpu.registers().get(Register.R2));
        assertEquals(3, cpu.pc());
    }

    @Test
    void movRegisterZeroExtendsHalf() {
        cpu.registers().set(Register.R1, 0x1234_5678);
        load(0, Opcode.MOV, InstructionForm.REGISTER, Register.R2, Register.R1,
                0, false, MemorySize.HALF, 0);

        step();

        assertEquals(0x5678, cpu.registers().get(Register.R2));
    }

    @Test
    void movLoadsAndStoresUseLittleEndianAndNaturalSizes() {
        memory.put32(0x100, 0x89AB_CDEF);
        cpu.registers().set(Register.R1, 0x100);
        cpu.registers().set(Register.R2, 0x1234_5678);

        load(0, Opcode.MOV, InstructionForm.LOAD, Register.R3, 0,
                Register.R1, false, MemorySize.WORD, 0);
        load(7, Opcode.MOV, InstructionForm.STORE, Register.R2, 0,
                Register.R1, false, MemorySize.WORD, 4);

        step();
        step();

        assertEquals(0x89AB_CDEF, cpu.registers().get(Register.R3));
        assertEquals(0x1234_5678, memory.read(0x104, MemorySize.WORD));
        assertEquals(14, cpu.pc());
    }

    @Test
    void byteLoadZeroExtendsAndByteStoreWritesLowBits() {
        memory.put32(0x100, 0x89AB_CDEF);
        cpu.registers().set(Register.R1, 0x100);
        cpu.registers().set(Register.R2, 0x1234_5678);

        load(0, Opcode.MOV, InstructionForm.LOAD, Register.R3, 0,
                Register.R1, false, MemorySize.BYTE, 0);
        load(7, Opcode.MOV, InstructionForm.STORE, Register.R2, 0,
                Register.R1, false, MemorySize.BYTE, 1);

        step();
        step();

        assertEquals(0xEF, cpu.registers().get(Register.R3));
        assertEquals(0x78, memory.read(0x101, MemorySize.BYTE));
    }

    @Test
    void indexedMemoryAddressIsBasePlusIndexPlusDisplacement() {
        memory.put32(0x124, 0xCAFE_BABE);
        cpu.registers().set(Register.R1, 0x100);
        cpu.registers().set(Register.R2, 0x20);

        load(0, Opcode.MOV, InstructionForm.LOAD, Register.R3, Register.R2,
                Register.R1, true, MemorySize.WORD, 4);

        step();

        assertEquals(0xCAFE_BABE, cpu.registers().get(Register.R3));
    }

    @Test
    void leaComputesTheAddressWithoutAccessingMemory() {
        cpu.registers().set(Register.R1, 0x100);
        cpu.registers().set(Register.R2, 0x20);

        load(0, Opcode.LEA, InstructionForm.LOAD, Register.R3, Register.R2,
                Register.R1, true, MemorySize.WORD, 4);

        step();

        assertEquals(0x124, cpu.registers().get(Register.R3));
        assertEquals(7, cpu.pc());
    }

    @Test
    void pushAndPopRoundTripThroughTheStack() {
        cpu.registers().set(Register.SP, 0x200);
        cpu.registers().set(Register.R1, 0xDEAD_BEEF);

        load(0, Opcode.PUSH, InstructionForm.REGISTER, 0, Register.R1, 0);
        load(3, Opcode.POP, InstructionForm.REGISTER, Register.R2, 0, 0);

        step();
        step();

        assertEquals(0xDEAD_BEEF, cpu.registers().get(Register.R2));
        assertEquals(0x200, cpu.registers().get(Register.SP));
        assertEquals(0xDEAD_BEEF, memory.read(0x1FC, MemorySize.WORD));
    }

    @Test
    void unalignedStackPointerTrapsWithoutWriting() {
        enableTrapVector();
        cpu.registers().set(Register.SP, 0x201);
        load(0, Opcode.PUSH, InstructionForm.REGISTER, 0, Register.R1, 0);

        step();

        assertTrap(MachineFault.Code.ALIGNMENT, 0, 0x201);
        assertEquals(0x201, cpu.registers().get(Register.SP));
        assertEquals(TVEC + 4 * MachineFault.Code.ALIGNMENT.vector(), cpu.pc());
    }

    @Test
    void addWritesResultAndFlags() {
        cpu.registers().set(Register.R1, 0x7FFF_FFFF);
        load(0, Opcode.ADD, InstructionForm.IMMEDIATE, Register.R1, 0, 1);

        step();

        assertEquals(0x8000_0000, cpu.registers().get(Register.R1));
        assertEquals(new Flags(false, true, false, true), cpu.csrs().flags());
        assertEquals(7, cpu.pc());
    }

    @Test
    void adcUsesTheCurrentCarryFlag() {
        cpu.csrs().setFlags(new Flags(false, false, true, false));
        cpu.registers().set(Register.R1, 0);
        load(0, Opcode.ADC, InstructionForm.IMMEDIATE, Register.R1, 0, 0);

        step();

        assertEquals(1, cpu.registers().get(Register.R1));
        assertEquals(new Flags(false, false, false, false), cpu.csrs().flags());
    }

    @Test
    void cmpUpdatesFlagsWithoutWritingRd() {
        cpu.registers().set(Register.R1, 5);
        load(0, Opcode.CMP, InstructionForm.IMMEDIATE, Register.R1, 0, 5);

        step();

        assertEquals(5, cpu.registers().get(Register.R1));
        assertEquals(new Flags(true, false, true, false), cpu.csrs().flags());
    }

    @Test
    void testUpdatesFlagsWithoutWritingRd() {
        cpu.registers().set(Register.R1, 0x8000_0000);
        load(0, Opcode.TEST, InstructionForm.IMMEDIATE, Register.R1, 0, 1);

        step();

        assertEquals(0x8000_0000, cpu.registers().get(Register.R1));
        assertEquals(new Flags(true, false, false, false), cpu.csrs().flags());
    }

    @Test
    void jmpImmediateUsesPcPlusLengthPlusDisplacement() {
        load(0, Opcode.JMP, InstructionForm.IMMEDIATE, 0, 0, 0x100);

        step();

        assertEquals(0x107, cpu.pc());
    }

    @Test
    void jmpRegisterUsesTheSourceRegister() {
        cpu.registers().set(Register.R2, 0x200);
        load(0, Opcode.JMP, InstructionForm.REGISTER, 0, Register.R2, 0);

        step();

        assertEquals(0x200, cpu.pc());
    }

    @Test
    void jccTakesWhenTheConditionIsTrue() {
        cpu.csrs().setFlags(new Flags(true, false, false, false));
        load(0, Opcode.JCC, InstructionForm.IMMEDIATE, 0, 0, 0x100);

        step();

        assertEquals(0x107, cpu.pc());
    }

    @Test
    void jccFallsThroughWhenTheConditionIsFalse() {
        cpu.csrs().setFlags(new Flags(false, false, false, false));
        load(0, Opcode.JCC, InstructionForm.IMMEDIATE, 0, 0, 0x100);

        step();

        assertEquals(7, cpu.pc());
    }

    @Test
    void jccImplementsEveryConditionCode() {
        boolean[] expectedWithZeroCarry = {
                true, false, true, false, false, true, false, true,
                false, true, true, false, false, true, true, false
        };
        boolean[] expectedWithoutZeroCarry = {
                false, true, false, true, true, false, false, true,
                false, true, false, true, false, true, true, false
        };

        assertConditions(new Flags(true, false, true, false), expectedWithZeroCarry);
        assertConditions(new Flags(false, true, false, false), expectedWithoutZeroCarry);
    }

    @Test
    void callReadsTheTargetBeforeWritingTheLinkRegister() {
        cpu.registers().set(Register.LR, 0x200);
        load(0, Opcode.CALL, InstructionForm.REGISTER, 0, Register.LR, 0);

        step();

        assertEquals(0x200, cpu.pc());
        assertEquals(3, cpu.registers().get(Register.LR));
    }

    @Test
    void callImmediateWritesTheReturnAddress() {
        load(0, Opcode.CALL, InstructionForm.IMMEDIATE, 0, 0, 0x100);

        step();

        assertEquals(0x107, cpu.pc());
        assertEquals(7, cpu.registers().get(Register.LR));
    }

    @Test
    void retJumpsToTheLinkRegister() {
        cpu.registers().set(Register.LR, 0x200);
        load(0, Opcode.RET, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertEquals(0x200, cpu.pc());
    }

    @Test
    void ecallFromUserUsesVectorSevenAndSavesPcPlusThree() {
        enableTrapVector();
        cpu.csrs().setStatus(Status.fromBits(Status.MMU));

        load(0, Opcode.ECALL, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.ECALL_USER, 3, null);
        assertEquals(TVEC + 4 * MachineFault.Code.ECALL_USER.vector(), cpu.pc());
        assertEquals(Privilege.SUPERVISOR, cpu.csrs().status().privilege());
        assertEquals(0, cpu.cycles());
    }

    @Test
    void ecallFromSupervisorUsesVectorEight() {
        enableTrapVector();
        load(0, Opcode.ECALL, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.ECALL_SUPERVISOR, 3, null);
        assertTrue(cpu.csrs().status().previousSupervisor());
    }

    @Test
    void userModeWithoutMmuTrapsPrivilegeBeforeExecution() {
        enableTrapVector();
        cpu.csrs().setStatus(Status.fromBits(0));
        load(0, Opcode.NOP, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.PRIVILEGE, 0, null);
        assertEquals(TVEC + 4 * MachineFault.Code.PRIVILEGE.vector(), cpu.pc());
        assertEquals(0, cpu.cycles());
    }

    @Test
    void eretRestoresStatusAndJumpsToEpc() {
        cpu.csrs().setStatus(Status.fromBits(Status.PRV | Status.PIE | Status.MMU | Status.TRAP));
        cpu.csrs().write(Csr.EPC, 0x200, 0);
        load(0, Opcode.ERET, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertEquals(0x200, cpu.pc());
        assertEquals(Status.IE | Status.PIE | Status.MMU, cpu.csrs().status().bits());
        assertEquals(Privilege.USER, cpu.csrs().status().privilege());
        assertFalse(cpu.csrs().status().trap());
        assertTrue(cpu.csrs().status().previousInterruptEnable());
    }

    @Test
    void eretWithoutAnActiveTrapIsIllegal() {
        enableTrapVector();
        load(0, Opcode.ERET, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.ILLEGAL_INSTRUCTION, 0, null);
        assertEquals(TVEC, cpu.pc());
    }

    @Test
    void eretToUserWithoutMmuWhileInTrapIsADoubleFault() {
        enableTrapVector();
        cpu.csrs().setStatus(Status.fromBits(Status.PRV | Status.PIE | Status.TRAP));
        cpu.csrs().write(Csr.EPC, 0x200, 0);
        load(0, Opcode.ERET, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.DOUBLE_FAULT, 0, null);
        assertEquals(TVEC + 4 * MachineFault.Code.DOUBLE_FAULT.vector(), cpu.pc());
    }

    @Test
    void eretFromUserIsPrivileged() {
        enableTrapVector();
        cpu.csrs().setStatus(Status.fromBits(Status.MMU));
        load(0, Opcode.ERET, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.PRIVILEGE, 0, null);
    }

    @Test
    void breakTrapsWithTheBreakpointVector() {
        enableTrapVector();
        load(0, Opcode.BREAK, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.BREAKPOINT, 0, null);
        assertEquals(TVEC + 4 * MachineFault.Code.BREAKPOINT.vector(), cpu.pc());
    }

    @Test
    void haltStopsTheCpuAfterAdvancingPc() {
        cpu.csrs().setStatus(Status.fromBits(Status.PRV | Status.IE));
        load(0, Opcode.HALT, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrue(cpu.halted());
        assertEquals(3, cpu.pc());
        assertEquals(1, cpu.cycles());
        assertFalse(cpu.step());
    }

    @Test
    void haltWithInterruptsDisabledIsIllegal() {
        enableTrapVector();
        load(0, Opcode.HALT, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.ILLEGAL_INSTRUCTION, 0, null);
        assertFalse(cpu.halted());
    }

    @Test
    void haltFromUserIsPrivileged() {
        enableTrapVector();
        cpu.csrs().setStatus(Status.fromBits(Status.MMU | Status.IE));
        load(0, Opcode.HALT, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.PRIVILEGE, 0, null);
        assertFalse(cpu.halted());
    }

    @Test
    void nopOnlyAdvancesPc() {
        load(0, Opcode.NOP, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertEquals(3, cpu.pc());
        assertEquals(1, cpu.cycles());
    }

    @Test
    void csrReadModifyWriteAliasesWork() {
        cpu.registers().set(Register.R2, 0xA5);
        load(0, Opcode.CSRRW, InstructionForm.IMMEDIATE, Register.R1, Register.R2,
                Csr.SCRATCH.address());
        load(7, Opcode.CSRRS, InstructionForm.IMMEDIATE, Register.R3, Register.R4,
                Csr.SCRATCH.address());
        load(14, Opcode.CSRRC, InstructionForm.IMMEDIATE, Register.R6, Register.R5,
                Csr.SCRATCH.address());
        load(21, Opcode.CSRR, InstructionForm.IMMEDIATE, Register.R7, 0,
                Csr.SCRATCH.address());
        load(28, Opcode.CSRW, InstructionForm.IMMEDIATE, 0, Register.R8,
                Csr.SCRATCH.address());

        step();
        assertEquals(0, cpu.registers().get(Register.R1));
        assertEquals(0xA5, cpu.csrs().read(Csr.SCRATCH));

        cpu.registers().set(Register.R4, 0x30);
        step();
        assertEquals(0xA5, cpu.registers().get(Register.R3));
        assertEquals(0xB5, cpu.csrs().read(Csr.SCRATCH));

        cpu.registers().set(Register.R5, 0x0F);
        step();
        assertEquals(0xB5, cpu.registers().get(Register.R6));
        assertEquals(0xB0, cpu.csrs().read(Csr.SCRATCH));

        step();
        assertEquals(0xB0, cpu.registers().get(Register.R7));

        cpu.registers().set(Register.R8, 0x55);
        step();
        assertEquals(0x55, cpu.csrs().read(Csr.SCRATCH));
        assertEquals(35, cpu.pc());
    }

    @Test
    void coreIdCsrReadsThePrivateCoreIndex() {
        CsrFile csrs = new CsrFile(5);
        Cpu core = new Cpu(0, memory, memory, () -> timeNanos, null, null, csrs);
        load(0, Opcode.CSRR, InstructionForm.IMMEDIATE, Register.R1, 0,
                Csr.COREID.address());

        assertTrue(core.step());

        assertEquals(5, core.registers().get(Register.R1));
        assertEquals(7, core.pc());
    }

    @Test
    void coreIdCsrWriteTrapsIllegal() {
        CsrFile csrs = new CsrFile(5);
        Cpu core = new Cpu(0, memory, memory, () -> timeNanos, null, null, csrs);
        csrs.write(Csr.TVEC, TVEC, 0);
        core.registers().set(Register.R2, 9);
        load(0, Opcode.CSRW, InstructionForm.IMMEDIATE, 0, Register.R2,
                Csr.COREID.address());

        assertTrue(core.step());

        assertEquals(MachineFault.Code.ILLEGAL_INSTRUCTION.vector(), core.csrs().cause());
        assertEquals(0, core.csrs().epc());
        assertEquals(TVEC, core.pc());
        assertEquals(5, core.csrs().read(Csr.COREID));
    }

    @Test
    void csrAccessFromUserIsPrivileged() {
        enableTrapVector();
        cpu.csrs().setStatus(Status.fromBits(Status.MMU));
        load(0, Opcode.CSRRW, InstructionForm.IMMEDIATE, Register.R1, Register.R2,
                Csr.SCRATCH.address());

        step();

        assertTrap(MachineFault.Code.PRIVILEGE, 0, null);
    }

    @Test
    void invalidStatusValueTrapsIllegal() {
        enableTrapVector();
        cpu.registers().set(Register.R2, 0x40);
        load(0, Opcode.CSRW, InstructionForm.IMMEDIATE, 0, Register.R2,
                Csr.STATUS.address());

        step();

        assertTrap(MachineFault.Code.ILLEGAL_INSTRUCTION, 0, null);
    }

    @Test
    void rdcycleSnapshotsRetiredInstructionsBeforeItself() {
        load(0, Opcode.NOP, InstructionForm.REGISTER, 0, 0, 0);
        load(3, Opcode.RDCYCLE, InstructionForm.REGISTER, Register.R1, Register.R2, 0);

        step();
        step();

        assertEquals(1, cpu.registers().get(Register.R1));
        assertEquals(0, cpu.registers().get(Register.R2));
        assertEquals(2, cpu.cycles());
    }

    @Test
    void rdtimeSnapshotsTheVirtualClockInTwoRegisters() {
        load(0, Opcode.RDTIME, InstructionForm.REGISTER, Register.R3, Register.R4, 0);

        step();

        assertEquals(2, cpu.registers().get(Register.R3));
        assertEquals(1, cpu.registers().get(Register.R4));
    }

    @Test
    void hostDelegatesAndInstallsStatusAndLength() {
        AtomicReference<HostCall> captured = new AtomicReference<>();
        Cpu hostCpu = cpuWithHost(call -> {
            captured.set(call);
            return new HostResult(HostStatus.OK, 7);
        });
        hostCpu.registers().set(Register.R0, 0x0100);
        hostCpu.registers().set(Register.R1, 0);
        hostCpu.registers().set(Register.R2, 0x200);
        hostCpu.registers().set(Register.R3, 4);
        hostCpu.registers().set(Register.R4, 0x300);
        hostCpu.registers().set(Register.R5, 8);
        load(0, Opcode.HOST, InstructionForm.REGISTER, 0, 0, 0);

        assertTrue(hostCpu.step());

        assertEquals(HostStatus.OK, hostCpu.registers().get(Register.R0));
        assertEquals(7, hostCpu.registers().get(Register.R1));
        assertEquals(0x200, hostCpu.registers().get(Register.R2));
        assertEquals(4, hostCpu.registers().get(Register.R3));
        assertEquals(0x300, hostCpu.registers().get(Register.R4));
        assertEquals(8, hostCpu.registers().get(Register.R5));
        assertEquals(3, hostCpu.pc());
        assertEquals(0x0100, captured.get().serviceId());
        assertEquals(4, captured.get().argumentLength());
        assertEquals(8, captured.get().resultCapacity());
        assertEquals(Privilege.SUPERVISOR, captured.get().privilege());
    }

    @Test
    void hostRejectsNonZeroFlagsWithoutCallingTheHandler() {
        Cpu hostCpu = cpuWithHost(call -> {
            throw new AssertionError("HOST must not be called");
        });
        hostCpu.registers().set(Register.R1, 1);
        load(0, Opcode.HOST, InstructionForm.REGISTER, 0, 0, 0);

        assertTrue(hostCpu.step());

        assertEquals(HostStatus.INVALID_ARGUMENT, hostCpu.registers().get(Register.R0));
        assertEquals(0, hostCpu.registers().get(Register.R1));
        assertEquals(3, hostCpu.pc());
    }

    @Test
    void hostRejectsOversizedBuffersWithoutCallingTheHandler() {
        Cpu hostCpu = cpuWithHost(call -> {
            throw new AssertionError("HOST must not be called");
        });
        hostCpu.registers().set(Register.R3, HostCall.MAX_BUFFER + 1);
        load(0, Opcode.HOST, InstructionForm.REGISTER, 0, 0, 0);

        assertTrue(hostCpu.step());

        assertEquals(HostStatus.INVALID_ARGUMENT, hostCpu.registers().get(Register.R0));
        assertEquals(0, hostCpu.registers().get(Register.R1));
    }

    @Test
    void hostWithoutAHandlerFailsExplicitly() {
        load(0, Opcode.HOST, InstructionForm.REGISTER, 0, 0, 0);

        assertThrows(IllegalStateException.class, () -> cpu.step());
    }

    @Test
    void hostRejectsArgumentBufferCrossingAddressSpace() {
        Cpu hostCpu = cpuWithHost(call -> {
            throw new AssertionError("HOST must not be called");
        });
        hostCpu.registers().set(Register.R2, 0xFFFF_FFFF);
        hostCpu.registers().set(Register.R3, 2);
        load(0, Opcode.HOST, InstructionForm.REGISTER, 0, 0, 0);

        assertTrue(hostCpu.step());

        assertEquals(HostStatus.FAULT, hostCpu.registers().get(Register.R0));
        assertEquals(0, hostCpu.registers().get(Register.R1));
        assertEquals(3, hostCpu.pc());
    }

    @Test
    void hostRejectsResultBufferCrossingAddressSpace() {
        Cpu hostCpu = cpuWithHost(call -> {
            throw new AssertionError("HOST must not be called");
        });
        hostCpu.registers().set(Register.R3, 0);
        hostCpu.registers().set(Register.R4, 0xFFFF_FFFF);
        hostCpu.registers().set(Register.R5, 2);
        load(0, Opcode.HOST, InstructionForm.REGISTER, 0, 0, 0);

        assertTrue(hostCpu.step());

        assertEquals(HostStatus.FAULT, hostCpu.registers().get(Register.R0));
        assertEquals(0, hostCpu.registers().get(Register.R1));
        assertEquals(3, hostCpu.pc());
    }

    @Test
    void hostMemoryFaultBecomesAFaultStatus() {
        Cpu hostCpu = cpuWithHost(call -> {
            call.argumentBytes();
            return new HostResult(HostStatus.OK, 0);
        });
        hostCpu.registers().set(Register.R2, 0xFFFF);
        hostCpu.registers().set(Register.R3, 4);
        load(0, Opcode.HOST, InstructionForm.REGISTER, 0, 0, 0);

        assertTrue(hostCpu.step());

        assertEquals(HostStatus.FAULT, hostCpu.registers().get(Register.R0));
        assertEquals(0, hostCpu.registers().get(Register.R1));
        assertEquals(3, hostCpu.pc());
    }

    @Test
    void fenceAndSfenceReachTheMemorySubsystem() {
        load(0, Opcode.FENCE, InstructionForm.REGISTER, 0, 0, 0);
        load(3, Opcode.SFENCE_VMA, InstructionForm.REGISTER, 0, 0, 0);

        step();
        step();

        assertTrue(memory.fenced);
        assertTrue(memory.tlbFlushed);
        assertEquals(6, cpu.pc());
    }

    @Test
    void sfenceFromUserIsPrivileged() {
        enableTrapVector();
        cpu.csrs().setStatus(Status.fromBits(Status.MMU));
        load(0, Opcode.SFENCE_VMA, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.PRIVILEGE, 0, null);
        assertFalse(memory.tlbFlushed);
    }

    @Test
    void fenceFromUserIsAllowed() {
        cpu.csrs().setStatus(Status.fromBits(Status.MMU));
        load(0, Opcode.FENCE, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrue(memory.fenced);
        assertEquals(3, cpu.pc());
    }

    @Test
    void unalignedWordLoadTrapsAlignment() {
        enableTrapVector();
        cpu.registers().set(Register.R1, 0x101);
        load(0, Opcode.MOV, InstructionForm.LOAD, Register.R2, 0,
                Register.R1, false, MemorySize.WORD, 0);

        step();

        assertTrap(MachineFault.Code.ALIGNMENT, 0, 0x101);
        assertEquals(TVEC + 4 * MachineFault.Code.ALIGNMENT.vector(), cpu.pc());
    }

    @Test
    void unalignedWordStoreTrapsAlignment() {
        enableTrapVector();
        cpu.registers().set(Register.R1, 0x102);
        load(0, Opcode.MOV, InstructionForm.STORE, Register.R2, 0,
                Register.R1, false, MemorySize.WORD, 0);

        step();

        assertTrap(MachineFault.Code.ALIGNMENT, 0, 0x102);
    }

    @Test
    void outOfRangeDataAccessTrapsBusError() {
        enableTrapVector();
        cpu.registers().set(Register.R1, 0x1_0000);
        load(0, Opcode.MOV, InstructionForm.LOAD, Register.R2, 0,
                Register.R1, false, MemorySize.WORD, 0);

        step();

        assertTrap(MachineFault.Code.BUS_ERROR, 0, 0x1_0000);
        assertEquals(TVEC + 4 * MachineFault.Code.BUS_ERROR.vector(), cpu.pc());
    }

    @Test
    void instructionFetchFaultKeepsTheInstructionPcAndBadAddress() {
        InstructionReader reader = address -> {
            if (address == 1) {
                throw MachineFault.instructionFault(address, "fetch fault");
            }
            return memory.readByte(address);
        };
        Cpu fetchCpu = new Cpu(0, reader, memory, () -> timeNanos);
        fetchCpu.csrs().write(Csr.TVEC, TVEC, 0);
        load(0, Opcode.NOP, InstructionForm.REGISTER, 0, 0, 0);

        assertTrue(fetchCpu.step());

        assertEquals(MachineFault.Code.INSTRUCTION_FAULT.vector(),
                fetchCpu.csrs().cause());
        assertEquals(0, fetchCpu.csrs().epc());
        assertEquals(1, fetchCpu.csrs().badvaddr());
        assertEquals(TVEC + 4 * MachineFault.Code.INSTRUCTION_FAULT.vector(),
                fetchCpu.pc());
    }

    @Test
    void unhandledTrapIsAnExplicitHostError() {
        load(0, Opcode.BREAK, InstructionForm.REGISTER, 0, 0, 0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> cpu.step());
        assertTrue(error.getMessage().startsWith("UNHANDLED_TRAP:"));
    }

    @Test
    void secondFaultEntersDoubleFaultAndASecondDoubleFaultAborts() {
        enableTrapVector();
        cpu.csrs().setStatus(Status.fromBits(Status.PRV | Status.TRAP));
        cpu.csrs().write(Csr.CAUSE, MachineFault.Code.ECALL_USER.vector(), 0);
        load(0, Opcode.BREAK, InstructionForm.REGISTER, 0, 0, 0);

        step();

        assertTrap(MachineFault.Code.DOUBLE_FAULT, 0, null);
        int doubleFaultPc = TVEC + 4 * MachineFault.Code.DOUBLE_FAULT.vector();
        assertEquals(doubleFaultPc, cpu.pc());

        memory.load(doubleFaultPc, new byte[] {(byte) 0xFF});
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> cpu.step());
        assertEquals("Double fault during double fault", error.getMessage());
    }

    private void assertConditions(Flags flags, boolean[] expected) {
        for (int code = 0; code < expected.length; code++) {
            cpu.reset(0);
            cpu.csrs().setFlags(flags);
            load(0, Opcode.JCC, InstructionForm.IMMEDIATE, 0, code, 0x100);

            step();

            int expectedPc = expected[code] ? 0x107 : 7;
            assertEquals(expectedPc, cpu.pc(), "condition " + code);
        }
    }

    private Cpu cpuWithHost(HostHandler handler) {
        return new Cpu(0, memory, memory, () -> timeNanos, handler);
    }

    private void enableTrapVector() {
        cpu.csrs().write(Csr.TVEC, TVEC, 0);
    }

    private void step() {
        assertTrue(cpu.step());
    }

    private void assertTrap(MachineFault.Code code, int epc, Integer badAddress) {
        assertEquals(code.vector(), cpu.csrs().cause());
        assertEquals(epc, cpu.csrs().epc());
        if (badAddress != null) {
            assertEquals(badAddress.intValue(), cpu.csrs().badvaddr());
        }
        assertTrue(cpu.csrs().status().trap());
        assertTrue(cpu.csrs().status().supervisor());
        assertFalse(cpu.csrs().status().interruptEnable());
    }

    private void load(int address, Opcode opcode, InstructionForm form, int rd, int rs, int immediate) {
        load(address, opcode, form, rd, rs, 0, false, MemorySize.WORD, immediate);
    }

    private void load(int address, Opcode opcode, InstructionForm form, int rd, int rs,
                      int base, boolean indexed, MemorySize size, int immediate) {
        memory.load(address, InstructionFixture.instruction(
                opcode.family(),
                opcode.operation(),
                form.code(),
                rd,
                rs,
                base,
                indexed ? 1 : 0,
                size.code(),
                immediate));
    }

    private static final class TestMemory implements InstructionReader, DataMemory {

        private final byte[] bytes = new byte[0x10000];
        private boolean fenced;
        private boolean tlbFlushed;

        @Override
        public int readByte(int address) {
            if (address < 0 || address >= bytes.length) {
                throw MachineFault.instructionFault(address, "instruction fetch out of range");
            }
            return bytes[address] & 0xFF;
        }

        @Override
        public int read(int address, MemorySize size) {
            check(address, size.bytes());
            int value = 0;
            for (int index = 0; index < size.bytes(); index++) {
                value |= (bytes[address + index] & 0xFF) << (8 * index);
            }
            return value;
        }

        @Override
        public void write(int address, MemorySize size, int value) {
            check(address, size.bytes());
            for (int index = 0; index < size.bytes(); index++) {
                bytes[address + index] = (byte) (value >>> (8 * index));
            }
        }

        @Override
        public void fence() {
            fenced = true;
        }

        @Override
        public void flushTlb() {
            tlbFlushed = true;
        }

        void load(int address, byte[] code) {
            check(address, code.length);
            System.arraycopy(code, 0, bytes, address, code.length);
        }

        void put32(int address, int value) {
            write(address, MemorySize.WORD, value);
        }

        private void check(int address, int length) {
            if (address < 0 || Integer.toUnsignedLong(address) + length > bytes.length) {
                throw MemoryFault.busError(address, "data access out of range");
            }
        }
    }
}

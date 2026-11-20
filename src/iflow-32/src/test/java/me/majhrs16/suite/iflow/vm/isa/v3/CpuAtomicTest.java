package me.majhrs16.suite.iflow.vm.isa.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import me.majhrs16.suite.iflow.vm.memory.v3.MachineFixture;
import me.majhrs16.suite.iflow.vm.memory.v3.Pic;

class CpuAtomicTest {

    private static final int CODE = MachineFixture.FRAME_A;
    private static final int DATA = CODE + 0x100;
    private static final int TVEC = CODE + 0x2000;

    private MachineFixture fixture;
    private Cpu cpu;

    @BeforeEach
    void setUp() {
        fixture = new MachineFixture();
        cpu = new Cpu(CODE, fixture.memory, fixture.memory, fixture.clock::get,
                null, fixture.memory, fixture.csrs);
    }

    @Test
    void loadLinkedAndStoreConditionalSucceedInTheSameCore() {
        fixture.put32(DATA, 0x1122_3344);
        load(
                mov(Register.R1, 0x1122_3344),
                mov(Register.R2, 0x5566_7788),
                mov(Register.R3, DATA),
                lr(Register.R4, Register.R3),
                sc(Register.R5, Register.R2, Register.R3));

        step(5);

        assertEquals(0x1122_3344, cpu.registers().get(Register.R4));
        assertEquals(1, cpu.registers().get(Register.R5));
        assertEquals(0x5566_7788, fixture.get32(DATA));
    }

    @Test
    void storeConditionalFailsWithoutPriorReservation() {
        fixture.put32(DATA, 0x1122_3344);
        load(
                mov(Register.R2, 0x5566_7788),
                mov(Register.R3, DATA),
                sc(Register.R5, Register.R2, Register.R3));

        step(3);

        assertEquals(0, cpu.registers().get(Register.R5));
        assertEquals(0x1122_3344, fixture.get32(DATA));
    }

    @Test
    void amoAddReturnsThePreviousValueAndWritesTheSum() {
        AmoResult result = runAmo(Opcode.AMOADD_W, 10, 5);

        assertEquals(10, result.previous());
        assertEquals(15, result.stored());
    }

    @Test
    void amoSwapReturnsThePreviousValueAndWritesTheOperand() {
        AmoResult result = runAmo(Opcode.AMOSWAP_W, 10, 5);

        assertEquals(10, result.previous());
        assertEquals(5, result.stored());
    }

    @Test
    void amoAndAndOrAreAtomicReadModifyWriteOperations() {
        AmoResult and = runAmo(Opcode.AMOAND_W, 0b1100, 0b1010);
        assertEquals(0b1100, and.previous());
        assertEquals(0b1000, and.stored());

        AmoResult or = runAmo(Opcode.AMOOR_W, 0b1100, 0b1010);
        assertEquals(0b1100, or.previous());
        assertEquals(0b1110, or.stored());
    }

    @Test
    void aTrapDropsTheLoadLinkedReservation() {
        fixture.put32(DATA, 0x1122_3344);
        fixture.csrs.write(Csr.TVEC, TVEC, 0);
        load(
                mov(Register.R2, 0x5566_7788),
                mov(Register.R3, DATA),
                lr(Register.R4, Register.R3),
                breakpoint());
        loadAt(TVEC, sc(Register.R5, Register.R2, Register.R3));

        step(5);

        assertEquals(0, cpu.registers().get(Register.R5));
        assertEquals(0x1122_3344, fixture.get32(DATA));
    }

    @Test
    void atomicAccessToMmioIsABusError() {
        fixture.csrs.write(Csr.TVEC, TVEC, 0);
        load(
                mov(Register.R3, Pic.BASE),
                lr(Register.R4, Register.R3));

        step(2);

        assertEquals(MachineFault.Code.BUS_ERROR.vector(), fixture.csrs.cause());
        assertEquals(TVEC + Integer.BYTES * MachineFault.Code.BUS_ERROR.vector(),
                cpu.pc());
    }

    @Test
    void atomicNonWordAccessIsIllegal() {
        fixture.csrs.write(Csr.TVEC, TVEC, 0);
        load(InstructionFixture.instruction(
                Opcode.LR_W.family(), Opcode.LR_W.operation(),
                InstructionForm.LOAD.code(), Register.R4, 0,
                Register.R3, 0, MemorySize.HALF.code(), 0));

        step(1);

        assertEquals(MachineFault.Code.ILLEGAL_INSTRUCTION.vector(),
                fixture.csrs.cause());
        assertEquals(TVEC, cpu.pc());
    }

    private AmoResult runAmo(Opcode opcode, int initial, int operand) {
        cpu.reset(CODE);
        fixture.put32(DATA, initial);
        load(
                mov(Register.R2, operand),
                mov(Register.R3, DATA),
                InstructionFixture.instruction(
                        opcode.family(), opcode.operation(),
                        InstructionForm.STORE.code(), Register.R4,
                        Register.R2, Register.R3, 0,
                        MemorySize.WORD.code(), 0));

        step(3);

        return new AmoResult(cpu.registers().get(Register.R4), fixture.get32(DATA));
    }

    private static byte[] mov(int rd, int value) {
        return InstructionFixture.instruction(
                Opcode.MOV.family(), Opcode.MOV.operation(),
                InstructionForm.IMMEDIATE.code(), rd, 0, 0, 0,
                MemorySize.WORD.code(), value);
    }

    private static byte[] lr(int rd, int base) {
        return InstructionFixture.instruction(
                Opcode.LR_W.family(), Opcode.LR_W.operation(),
                InstructionForm.LOAD.code(), rd, 0, base, 0,
                MemorySize.WORD.code(), 0);
    }

    private static byte[] sc(int rd, int value, int base) {
        return InstructionFixture.instruction(
                Opcode.SC_W.family(), Opcode.SC_W.operation(),
                InstructionForm.STORE.code(), rd, value,
                base, 0, MemorySize.WORD.code(), 0);
    }

    private static byte[] breakpoint() {
        return InstructionFixture.instruction(
                Opcode.BREAK.family(), Opcode.BREAK.operation(),
                InstructionForm.REGISTER.code(), 0, 0, 0, 0,
                MemorySize.WORD.code(), 0);
    }

    private void load(byte[]... instructions) {
        loadAt(CODE, instructions);
    }

    private void loadAt(int address, byte[]... instructions) {
        int offset = 0;
        for (byte[] instruction : instructions) {
            int[] bytes = new int[instruction.length];
            for (int index = 0; index < instruction.length; index++) {
                bytes[index] = instruction[index] & 0xFF;
            }
            fixture.loadPhysical(address + offset, bytes);
            offset += instruction.length;
        }
    }

    private void step(int count) {
        for (int index = 0; index < count; index++) {
            assertTrue(cpu.step());
        }
    }

    private record AmoResult(int previous, int stored) {
    }
}

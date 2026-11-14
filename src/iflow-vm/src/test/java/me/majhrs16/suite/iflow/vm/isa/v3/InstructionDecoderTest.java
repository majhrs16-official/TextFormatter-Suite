package me.majhrs16.suite.iflow.vm.isa.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InstructionDecoderTest {

    private static final int R = InstructionForm.REGISTER.code();
    private static final int I = InstructionForm.IMMEDIATE.code();
    private static final int L = InstructionForm.LOAD.code();
    private static final int S = InstructionForm.STORE.code();

    private static final int BYTE = MemorySize.BYTE.code();
    private static final int HALF = MemorySize.HALF.code();
    private static final int WORD = MemorySize.WORD.code();

    private static byte[] encode(Opcode opcode, int form, int rd, int rs,
                                 int base, int idx, int size, int immediate) {
        return InstructionFixture.instruction(
                opcode.family(), opcode.operation(), form, rd, rs, base, idx, size, immediate);
    }

    @Test
    void decodesMovRegister() {
        Instruction instruction = InstructionFixture.decode(
                encode(Opcode.MOV, R, 1, 2, 0, 0, WORD, 0));

        assertEquals(Opcode.MOV, instruction.opcode());
        assertEquals(InstructionForm.REGISTER, instruction.form());
        assertEquals(1, instruction.rd());
        assertEquals(2, instruction.rs());
        assertEquals(0, instruction.base());
        assertFalse(instruction.indexed());
        assertEquals(MemorySize.WORD, instruction.size());
        assertEquals(0, instruction.immediate());
        assertEquals(3, instruction.length());
    }

    @Test
    void decodesMovImmediate() {
        Instruction instruction = InstructionFixture.decode(
                encode(Opcode.MOV, I, 3, 0, 0, 0, WORD, 0x12345678));

        assertEquals(Opcode.MOV, instruction.opcode());
        assertEquals(InstructionForm.IMMEDIATE, instruction.form());
        assertEquals(3, instruction.rd());
        assertEquals(WORD, instruction.size().code());
        assertEquals(0x12345678, instruction.immediate());
        assertEquals(7, instruction.length());
    }

    @Test
    void decodesMovLoadWithIndex() {
        Instruction instruction = InstructionFixture.decode(
                encode(Opcode.MOV, L, 1, 2, 3, 1, BYTE, 0x10));

        assertTrue(instruction.load());
        assertTrue(instruction.indexed());
        assertEquals(1, instruction.rd());
        assertEquals(2, instruction.rs());
        assertEquals(3, instruction.base());
        assertEquals(MemorySize.BYTE, instruction.size());
        assertEquals(0x10, instruction.immediate());
        assertEquals(7, instruction.length());
    }

    @Test
    void decodesMovStoreWithoutIndex() {
        Instruction instruction = InstructionFixture.decode(
                encode(Opcode.MOV, S, 4, 0, 5, 0, WORD, -4));

        assertTrue(instruction.store());
        assertFalse(instruction.indexed());
        assertEquals(4, instruction.rd());
        assertEquals(0, instruction.rs());
        assertEquals(5, instruction.base());
        assertEquals(MemorySize.WORD, instruction.size());
        assertEquals(-4, instruction.immediate());
        assertEquals(7, instruction.length());
    }

    @Test
    void decodesLea() {
        Instruction instruction = InstructionFixture.decode(
                encode(Opcode.LEA, L, 1, 0, 2, 0, HALF, 0x1000));

        assertEquals(Opcode.LEA, instruction.opcode());
        assertEquals(InstructionForm.LOAD, instruction.form());
        assertEquals(1, instruction.rd());
        assertEquals(2, instruction.base());
        assertEquals(0x1000, instruction.immediate());
        assertEquals(7, instruction.length());
    }

    @Test
    void decodesAluRegisterAndImmediate() {
        Instruction register = InstructionFixture.decode(
                encode(Opcode.ADD, R, 3, 4, 0, 0, WORD, 0));
        Instruction immediate = InstructionFixture.decode(
                encode(Opcode.ADD, I, 3, 0, 0, 0, WORD, 7));

        assertEquals(Opcode.ADD, register.opcode());
        assertEquals(3, register.rd());
        assertEquals(4, register.rs());
        assertEquals(3, register.length());

        assertEquals(Opcode.ADD, immediate.opcode());
        assertEquals(7, immediate.immediate());
        assertEquals(7, immediate.length());
    }

    @Test
    void decodesJcc() {
        Instruction instruction = InstructionFixture.decode(
                encode(Opcode.JCC, I, 0, 14, 0, 0, BYTE, 0x20));

        assertEquals(Opcode.JCC, instruction.opcode());
        assertEquals(0, instruction.rd());
        assertEquals(14, instruction.rs());
        assertEquals(0x20, instruction.immediate());
    }

    @Test
    void decodesCsr() {
        Instruction instruction = InstructionFixture.decode(
                encode(Opcode.CSRRW, I, 1, 2, 0, 0, BYTE, Csr.STATUS.address()));

        assertEquals(Opcode.CSRRW, instruction.opcode());
        assertEquals(1, instruction.rd());
        assertEquals(2, instruction.rs());
        assertEquals(Csr.STATUS.address(), instruction.immediate());
    }

    @Test
    void decodesRdcycle() {
        Instruction instruction = InstructionFixture.decode(
                encode(Opcode.RDCYCLE, R, 1, 2, 0, 0, BYTE, 0));

        assertEquals(Opcode.RDCYCLE, instruction.opcode());
        assertEquals(1, instruction.rd());
        assertEquals(2, instruction.rs());
    }

    @Test
    void decodesEveryDocumentedOpcodeForm() {
        for (Opcode opcode : Opcode.values()) {
            for (InstructionForm form : opcode.forms()) {
                int rd = 1;
                int rs = 2;
                int base = 0;
                int idx = 0;
                int size = WORD;
                int immediate = 0;

                if (form.isMemory()) {
                    base = 3;
                    idx = 1;
                    size = HALF;
                }
                if (opcode == Opcode.MOV && form == InstructionForm.REGISTER) {
                    size = BYTE;
                }
                if (opcode == Opcode.LEA) {
                    size = BYTE;
                }
                if (opcode == Opcode.JCC) {
                    rd = 0;
                    rs = 14;
                    immediate = 0x20;
                }
                if (opcode == Opcode.CSRRW || opcode == Opcode.CSRRS
                        || opcode == Opcode.CSRRC || opcode == Opcode.CSRR
                        || opcode == Opcode.CSRW) {
                    immediate = Csr.STATUS.address();
                }
                if (opcode == Opcode.JMP || opcode == Opcode.CALL) {
                    immediate = 0x40;
                }

                Instruction instruction = InstructionFixture.decode(
                        encode(opcode, form.code(), rd, rs, base, idx, size, immediate));

                assertEquals(opcode, instruction.opcode(), opcode.mnemonic());
                assertEquals(form, instruction.form(), opcode.mnemonic());
                assertEquals(form.length(), instruction.length(), opcode.mnemonic());
            }
        }
    }

    @Test
    void rejectsReservedOpcodeCombinations() {
        for (int[] reserved : new int[][]{{0, 4}, {0, 15}, {2, 9}, {2, 15}, {3, 10}, {3, 15}}) {
            InstructionFixture.assertIllegal(InstructionFixture.instruction(
                    reserved[0], reserved[1], R, 0, 0, 0, 0, WORD, 0));
        }
    }

    @Test
    void rejectsUnsupportedForms() {
        InstructionFixture.assertIllegal(encode(Opcode.ADD, L, 1, 0, 0, 0, WORD, 0));
        InstructionFixture.assertIllegal(encode(Opcode.PUSH, I, 0, 1, 0, 0, WORD, 0));
        InstructionFixture.assertIllegal(encode(Opcode.JCC, R, 0, 0, 0, 0, WORD, 0));
    }

    @Test
    void rejectsReservedDescriptorBit() {
        byte[] code = encode(Opcode.MOV, R, 1, 2, 0, 0, WORD, 0);
        code[1] |= 1;
        InstructionFixture.assertIllegal(code);
    }

    @Test
    void rejectsReservedSize() {
        InstructionFixture.assertIllegal(encode(Opcode.MOV, R, 1, 2, 0, 0, 3, 0));
    }

    @Test
    void rejectsBaseOrIndexOutsideMemoryForms() {
        InstructionFixture.assertIllegal(encode(Opcode.MOV, R, 1, 2, 1, 0, WORD, 0));
        InstructionFixture.assertIllegal(encode(Opcode.MOV, I, 1, 0, 0, 1, WORD, 0));
    }

    @Test
    void rejectsUnusedIndexRegisterInMemoryForms() {
        InstructionFixture.assertIllegal(encode(Opcode.MOV, L, 1, 1, 2, 0, WORD, 0));
    }

    @Test
    void rejectsMovImmediateWithByteSize() {
        InstructionFixture.assertIllegal(encode(Opcode.MOV, I, 1, 0, 0, 0, BYTE, 1));
    }

    @Test
    void rejectsAluWithNonWordSize() {
        InstructionFixture.assertIllegal(encode(Opcode.ADD, R, 1, 2, 0, 0, BYTE, 0));
        InstructionFixture.assertIllegal(encode(Opcode.ADD, I, 1, 0, 0, 0, HALF, 1));
    }

    @Test
    void rejectsPushWithNonWordSize() {
        InstructionFixture.assertIllegal(encode(Opcode.PUSH, R, 0, 1, 0, 0, HALF, 0));
    }

    @Test
    void rejectsRdcycleWithSameRegister() {
        InstructionFixture.assertIllegal(encode(Opcode.RDCYCLE, R, 1, 1, 0, 0, WORD, 0));
    }

    @Test
    void rejectsUndefinedCsr() {
        InstructionFixture.assertIllegal(encode(Opcode.CSRRW, I, 1, 2, 0, 0, WORD, 0x10A));
    }

    @Test
    void rejectsCsrWithHighImmediateBits() {
        InstructionFixture.assertIllegal(
                encode(Opcode.CSRRW, I, 1, 2, 0, 0, WORD, 0x1000_0100));
    }

    @Test
    void rejectsJccWithDataRegister() {
        InstructionFixture.assertIllegal(encode(Opcode.JCC, I, 1, 0, 0, 0, WORD, 0));
    }

    @Test
    void propagatesReaderFault() {
        MachineFault expected = MachineFault.instructionFault(0, "boom");
        InstructionReader reader = address -> {
            if (address == 1) {
                throw expected;
            }
            return 0;
        };

        MachineFault actual = assertThrows(MachineFault.class,
                () -> new InstructionDecoder().decode(0, reader));

        assertSame(expected, actual);
    }

    @Test
    void rejectsFetchCrossingAddressSpace() {
        MachineFault fault = assertThrows(MachineFault.class,
                () -> new InstructionDecoder().decode(0xFFFF_FFFE, address -> 0));

        assertEquals(MachineFault.Code.INSTRUCTION_FAULT, fault.code());
    }
}

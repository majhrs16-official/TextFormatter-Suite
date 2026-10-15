package me.majhrs16.suite.iflow.vm.isa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class OpcodeTest {

    @Test
    void allOpcodesAreValid() {
        for (int i = 0; i <= 0x6F; i++) {
            if (Opcode.isValid(i)) {
                assertNotNull(Opcode.name(i), "Opcode 0x" + Integer.toHexString(i).toUpperCase() + " has null name");
                assertTrue(Opcode.instructionSize(i) > 0, "Opcode 0x" + Integer.toHexString(i).toUpperCase() + " has invalid size");
            }
        }
    }

    @Test
    void invalidOpcodesReturnFalse() {
        assertFalse(Opcode.isValid(0x70));
        assertFalse(Opcode.isValid(0xF0));
        assertFalse(Opcode.isValid(0xFF));
        assertFalse(Opcode.isValid(-1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                         0x20, 0x21, 0x22, 0x23, 0x24,
                         0x28, 0x29, 0x2A, 0x2B,
                         0x2C, 0x2D, 0x2E,
                         0x30, 0x31,
                         0x40, 0x41, 0x42, 0x43, 0x44, 0x48, 0x49,
                         0x60, 0x68, 0x69})
    void validOpcodesReturnTrue(int opcode) {
        assertTrue(Opcode.isValid(opcode), "Opcode 0x" + Integer.toHexString(opcode).toUpperCase() + " should be valid");
    }

    @Test
    void instructionSizesMatchExpected() {
        // LI = RI16 = 4 bytes
        assertEquals(4, Opcode.instructionSize(Opcode.LI));

        // MOV = 2 bytes (RR)
        assertEquals(2, Opcode.instructionSize(Opcode.MOV));

        // NOT, RET = 2 bytes (RR), HALT = 1 byte (R)
        assertEquals(2, Opcode.instructionSize(Opcode.NOT));
        assertEquals(2, Opcode.instructionSize(Opcode.RET));
        assertEquals(1, Opcode.instructionSize(Opcode.HALT));

        // Memory = 4 bytes (MEM format: opcode + reg + vaddr16)
        assertEquals(4, Opcode.instructionSize(Opcode.LDB));
        assertEquals(4, Opcode.instructionSize(Opcode.LDW));
        assertEquals(4, Opcode.instructionSize(Opcode.STB));
        assertEquals(4, Opcode.instructionSize(Opcode.STW));

        // Block = 8 bytes (BLOCK format: opcode + srcVaddr16 + dstVaddr16 + len16)
        assertEquals(8, Opcode.instructionSize(Opcode.COPY));
        assertEquals(8, Opcode.instructionSize(Opcode.MOVE));

        // Arithmetic/Logic/Shifts = 2 bytes (RR)
        assertEquals(2, Opcode.instructionSize(Opcode.ADD));
        assertEquals(2, Opcode.instructionSize(Opcode.SUB));
        assertEquals(2, Opcode.instructionSize(Opcode.MUL));
        assertEquals(2, Opcode.instructionSize(Opcode.DIV));
        assertEquals(2, Opcode.instructionSize(Opcode.MOD));
        assertEquals(2, Opcode.instructionSize(Opcode.AND));
        assertEquals(2, Opcode.instructionSize(Opcode.OR));
        assertEquals(2, Opcode.instructionSize(Opcode.XOR));
        assertEquals(2, Opcode.instructionSize(Opcode.SHL));
        assertEquals(2, Opcode.instructionSize(Opcode.SHR));
        assertEquals(2, Opcode.instructionSize(Opcode.SAR));
        assertEquals(2, Opcode.instructionSize(Opcode.CMP));
        assertEquals(2, Opcode.instructionSize(Opcode.CMPU));

        // Flow control = 3 bytes (BR16)
        assertEquals(3, Opcode.instructionSize(Opcode.JMP));
        assertEquals(3, Opcode.instructionSize(Opcode.JZ));
        assertEquals(3, Opcode.instructionSize(Opcode.JNZ));
        assertEquals(3, Opcode.instructionSize(Opcode.JC));
        assertEquals(3, Opcode.instructionSize(Opcode.JV));
        assertEquals(3, Opcode.instructionSize(Opcode.CALL));

        // Services = 2 bytes (HOST)
        assertEquals(2, Opcode.instructionSize(Opcode.HOST));

        // Control = 2 bytes (TRAP = R + imm8)
        assertEquals(2, Opcode.instructionSize(Opcode.TRAP));
    }

    @Test
    void opcodeNamesAreCorrect() {
        assertEquals("LI", Opcode.name(Opcode.LI));
        assertEquals("MOV", Opcode.name(Opcode.MOV));
        assertEquals("LDB", Opcode.name(Opcode.LDB));
        assertEquals("LDW", Opcode.name(Opcode.LDW));
        assertEquals("STB", Opcode.name(Opcode.STB));
        assertEquals("STW", Opcode.name(Opcode.STW));
        assertEquals("COPY", Opcode.name(Opcode.COPY));
        assertEquals("MOVE", Opcode.name(Opcode.MOVE));
        assertEquals("ADD", Opcode.name(Opcode.ADD));
        assertEquals("SUB", Opcode.name(Opcode.SUB));
        assertEquals("MUL", Opcode.name(Opcode.MUL));
        assertEquals("DIV", Opcode.name(Opcode.DIV));
        assertEquals("MOD", Opcode.name(Opcode.MOD));
        assertEquals("AND", Opcode.name(Opcode.AND));
        assertEquals("OR", Opcode.name(Opcode.OR));
        assertEquals("XOR", Opcode.name(Opcode.XOR));
        assertEquals("NOT", Opcode.name(Opcode.NOT));
        assertEquals("SHL", Opcode.name(Opcode.SHL));
        assertEquals("SHR", Opcode.name(Opcode.SHR));
        assertEquals("SAR", Opcode.name(Opcode.SAR));
        assertEquals("CMP", Opcode.name(Opcode.CMP));
        assertEquals("CMPU", Opcode.name(Opcode.CMPU));
        assertEquals("JMP", Opcode.name(Opcode.JMP));
        assertEquals("JZ", Opcode.name(Opcode.JZ));
        assertEquals("JNZ", Opcode.name(Opcode.JNZ));
        assertEquals("JC", Opcode.name(Opcode.JC));
        assertEquals("JV", Opcode.name(Opcode.JV));
        assertEquals("CALL", Opcode.name(Opcode.CALL));
        assertEquals("RET", Opcode.name(Opcode.RET));
        assertEquals("HOST", Opcode.name(Opcode.HOST));
        assertEquals("HALT", Opcode.name(Opcode.HALT));
        assertEquals("TRAP", Opcode.name(Opcode.TRAP));
    }

    @Test
    void unknownOpcodeReturnsFormattedString() {
        String name = Opcode.name(0xFF);
        assertTrue(name.contains("UNKNOWN"));
        assertTrue(name.contains("FF"));
    }
}
package me.majhrs16.suite.iflow.vm.isa.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RegisterFileTest {

    @Test
    void storesAndLoadsAllGeneralPurposeRegisters() {
        RegisterFile registers = new RegisterFile();

        for (int register = 0; register < Register.COUNT; register++) {
            registers.set(register, register * 0x0101_0101);
        }

        for (int register = 0; register < Register.COUNT; register++) {
            assertEquals(register * 0x0101_0101, registers.get(register));
        }
    }

    @Test
    void resetClearsEveryRegister() {
        RegisterFile registers = new RegisterFile();
        registers.set(Register.FP, 1);
        registers.set(Register.SP, 2);
        registers.set(Register.LR, 3);

        registers.reset();

        for (int register = 0; register < Register.COUNT; register++) {
            assertEquals(0, registers.get(register));
        }
    }

    @Test
    void snapshotIsIndependentOfLaterWrites() {
        RegisterFile registers = new RegisterFile();
        registers.set(Register.R0, 42);

        int[] snapshot = registers.snapshot();
        registers.set(Register.R0, 99);

        assertArrayEquals(new int[] {42, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, snapshot);
        assertEquals(99, registers.get(Register.R0));
    }

    @Test
    void rejectsRegistersOutsideTheArchitecturalBank() {
        RegisterFile registers = new RegisterFile();

        assertThrows(IllegalArgumentException.class, () -> registers.get(-1));
        assertThrows(IllegalArgumentException.class, () -> registers.get(Register.COUNT));
        assertThrows(IllegalArgumentException.class, () -> registers.set(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> registers.set(Register.COUNT, 0));
    }
}

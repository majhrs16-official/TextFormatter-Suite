package me.majhrs16.suite.iflow.vm.isa.v3;

import java.util.Arrays;

/**
 * Sixteen 32-bit general-purpose registers.
 */
public final class RegisterFile {

    private final int[] registers = new int[Register.COUNT];

    public int get(int register) {
        return registers[checked(register)];
    }

    public void set(int register, int value) {
        registers[checked(register)] = value;
    }

    public void reset() {
        Arrays.fill(registers, 0);
    }

    public int[] snapshot() {
        return registers.clone();
    }

    private static int checked(int register) {
        if (!Register.isValid(register)) {
            throw new IllegalArgumentException("Invalid register: " + register);
        }
        return register;
    }
}

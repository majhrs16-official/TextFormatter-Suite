package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * General-purpose register identifiers for the v3 ISA.
 */
public final class Register {

    public static final int R0 = 0;
    public static final int R1 = 1;
    public static final int R2 = 2;
    public static final int R3 = 3;
    public static final int R4 = 4;
    public static final int R5 = 5;
    public static final int R6 = 6;
    public static final int R7 = 7;
    public static final int R8 = 8;
    public static final int R9 = 9;
    public static final int R10 = 10;
    public static final int R11 = 11;
    public static final int R12 = 12;
    public static final int FP = 13;
    public static final int SP = 14;
    public static final int LR = 15;

    public static final int COUNT = 16;

    private Register() {
    }

    public static boolean isValid(int register) {
        return register >= 0 && register < COUNT;
    }

    public static String name(int register) {
        return switch (register) {
            case R0 -> "R0";
            case R1 -> "R1";
            case R2 -> "R2";
            case R3 -> "R3";
            case R4 -> "R4";
            case R5 -> "R5";
            case R6 -> "R6";
            case R7 -> "R7";
            case R8 -> "R8";
            case R9 -> "R9";
            case R10 -> "R10";
            case R11 -> "R11";
            case R12 -> "R12";
            case FP -> "FP";
            case SP -> "SP";
            case LR -> "LR";
            default -> throw new IllegalArgumentException("Invalid register: " + register);
        };
    }
}

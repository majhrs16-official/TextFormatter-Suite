package me.majhrs16.suite.iflow.vm.isa.v3;

import java.util.Objects;

/**
 * Pure 32-bit ALU operations of the v3 ISA.
 *
 * <p>The methods return the architectural result and the flags that the
 * instruction must install. CMP and TEST return their computed value but the
 * caller must not write it back. DIV and MOD use the instruction address to
 * report a {@link MachineFault}.</p>
 */
public final class Alu {

    private Alu() {
    }

    public record Result(int value, Flags flags) {
        public static Result from(int value) {
            return new Result(value, Flags.fromResult(value));
        }

        public static Result from(int value, boolean carry, boolean overflow) {
            return new Result(value, Flags.fromResult(value, carry, overflow));
        }
    }

    public static Result add(int a, int b) {
        return add(a, b, false);
    }

    public static Result add(int a, int b, boolean carryIn) {
        long sum = Integer.toUnsignedLong(a) + Integer.toUnsignedLong(b) + (carryIn ? 1L : 0L);
        int value = (int) sum;
        boolean carry = (sum >>> 32) != 0;
        boolean overflow = ((a ^ value) & (b ^ value)) < 0;
        return Result.from(value, carry, overflow);
    }

    public static Result sub(int a, int b) {
        int value = a - b;
        boolean carry = Integer.compareUnsigned(a, b) >= 0;
        boolean overflow = ((a ^ b) & (a ^ value)) < 0;
        return Result.from(value, carry, overflow);
    }

    public static Result and(int a, int b) {
        return Result.from(a & b);
    }

    public static Result or(int a, int b) {
        return Result.from(a | b);
    }

    public static Result xor(int a, int b) {
        return Result.from(a ^ b);
    }

    public static Result shl(int value, int countOperand, Flags currentFlags) {
        Objects.requireNonNull(currentFlags, "currentFlags");
        int count = countOperand & 0x1F;
        if (count == 0) {
            return new Result(value, currentFlags);
        }
        int shifted = value << count;
        boolean carry = ((value >>> (32 - count)) & 1) != 0;
        return Result.from(shifted, carry, false);
    }

    public static Result shr(int value, int countOperand, Flags currentFlags) {
        Objects.requireNonNull(currentFlags, "currentFlags");
        int count = countOperand & 0x1F;
        if (count == 0) {
            return new Result(value, currentFlags);
        }
        int shifted = value >>> count;
        boolean carry = ((value >>> (count - 1)) & 1) != 0;
        return Result.from(shifted, carry, false);
    }

    public static Result sar(int value, int countOperand, Flags currentFlags) {
        Objects.requireNonNull(currentFlags, "currentFlags");
        int count = countOperand & 0x1F;
        if (count == 0) {
            return new Result(value, currentFlags);
        }
        int shifted = value >> count;
        boolean carry = ((value >> (count - 1)) & 1) != 0;
        return Result.from(shifted, carry, false);
    }

    public static Result mul(int a, int b) {
        return Result.from(a * b);
    }

    public static Result div(int dividend, int divisor, int pc) {
        if (divisor == 0) {
            throw MachineFault.divideByZero(pc);
        }
        if (dividend == Integer.MIN_VALUE && divisor == -1) {
            throw MachineFault.arithmeticOverflow(pc, "INT_MIN / -1");
        }
        return Result.from(dividend / divisor);
    }

    public static Result divu(int dividend, int divisor, int pc) {
        if (divisor == 0) {
            throw MachineFault.divideByZero(pc);
        }
        return Result.from(Integer.divideUnsigned(dividend, divisor));
    }

    public static Result mod(int dividend, int divisor, int pc) {
        if (divisor == 0) {
            throw MachineFault.divideByZero(pc);
        }
        if (dividend == Integer.MIN_VALUE && divisor == -1) {
            throw MachineFault.arithmeticOverflow(pc, "INT_MIN % -1");
        }
        return Result.from(dividend % divisor);
    }

    public static Result modu(int dividend, int divisor, int pc) {
        if (divisor == 0) {
            throw MachineFault.divideByZero(pc);
        }
        return Result.from(Integer.remainderUnsigned(dividend, divisor));
    }

    public static Result cmp(int a, int b) {
        return sub(a, b);
    }

    public static Result test(int a, int b) {
        return and(a, b);
    }
}

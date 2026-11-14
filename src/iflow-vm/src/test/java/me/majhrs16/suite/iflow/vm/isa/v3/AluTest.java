package me.majhrs16.suite.iflow.vm.isa.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AluTest {

    private static void assertFlags(Alu.Result result,
                                    boolean zero, boolean negative, boolean carry, boolean overflow) {
        assertEquals(new Flags(zero, negative, carry, overflow), result.flags());
    }

    @Test
    void addSetsCarryAndOverflow() {
        Alu.Result result = Alu.add(0x7FFF_FFFF, 1);

        assertEquals(0x8000_0000, result.value());
        assertFlags(result, false, true, false, true);
    }

    @Test
    void addSetsCarryOutAndZero() {
        Alu.Result result = Alu.add(0xFFFF_FFFF, 1);

        assertEquals(0, result.value());
        assertFlags(result, true, false, true, false);
    }

    @Test
    void addNegativeOverflowSetsCarryAndOverflow() {
        Alu.Result result = Alu.add(0x8000_0000, 0x8000_0000);

        assertEquals(0, result.value());
        assertFlags(result, true, false, true, true);
    }

    @Test
    void addNegativeWithoutOverflow() {
        Alu.Result result = Alu.add(0xFFFF_FFFF, 0xFFFF_FFFF);

        assertEquals(0xFFFF_FFFE, result.value());
        assertFlags(result, false, true, true, false);
    }

    @Test
    void addWithCarryInSetsOverflow() {
        Alu.Result result = Alu.add(0x7FFF_FFFF, 0, true);

        assertEquals(0x8000_0000, result.value());
        assertFlags(result, false, true, false, true);
    }

    @Test
    void addWithCarryInSetsCarryOut() {
        Alu.Result result = Alu.add(0xFFFF_FFFF, 0, true);

        assertEquals(0, result.value());
        assertFlags(result, true, false, true, false);
    }

    @Test
    void addWithCarryInDoesNotOverflow() {
        Alu.Result result = Alu.add(0x7FFF_FFFF, 0xFFFF_FFFF, true);

        assertEquals(0x7FFF_FFFF, result.value());
        assertFlags(result, false, false, true, false);
    }

    @Test
    void subSetsBorrow() {
        Alu.Result result = Alu.sub(0, 1);

        assertEquals(0xFFFF_FFFF, result.value());
        assertFlags(result, false, true, false, false);
    }

    @Test
    void subWithoutBorrowSetsCarry() {
        Alu.Result result = Alu.sub(1, 0);

        assertEquals(1, result.value());
        assertFlags(result, false, false, true, false);
    }

    @Test
    void subNegativeOverflow() {
        Alu.Result result = Alu.sub(0x8000_0000, 1);

        assertEquals(0x7FFF_FFFF, result.value());
        assertFlags(result, false, false, true, true);
    }

    @Test
    void subPositiveOverflow() {
        Alu.Result result = Alu.sub(0x7FFF_FFFF, 0xFFFF_FFFF);

        assertEquals(0x8000_0000, result.value());
        assertFlags(result, false, true, false, true);
    }

    @Test
    void logicalOperationsUpdateOnlyZeroAndNegative() {
        Alu.Result and = Alu.and(0xF0F0_F0F0, 0x0F0F_0F0F);
        assertEquals(0, and.value());
        assertFlags(and, true, false, false, false);

        Alu.Result or = Alu.or(0x8000_0000, 0);
        assertEquals(0x8000_0000, or.value());
        assertFlags(or, false, true, false, false);

        Alu.Result xor = Alu.xor(0xFFFF_FFFF, 0xFFFF_FFFF);
        assertEquals(0, xor.value());
        assertFlags(xor, true, false, false, false);
    }

    @Test
    void testUpdatesOnlyZeroAndNegative() {
        Alu.Result zero = Alu.test(0x8000_0000, 1);
        assertEquals(0, zero.value());
        assertFlags(zero, true, false, false, false);

        Alu.Result negative = Alu.test(0x8000_0000, 0x8000_0000);
        assertEquals(0x8000_0000, negative.value());
        assertFlags(negative, false, true, false, false);
    }

    @Test
    void shlUpdatesCarryAndClearsOverflow() {
        Alu.Result result = Alu.shl(0x8000_0000, 1, Flags.CLEAR);

        assertEquals(0, result.value());
        assertFlags(result, true, false, true, false);
    }

    @Test
    void shlByThirtyOne() {
        Alu.Result result = Alu.shl(1, 31, Flags.CLEAR);

        assertEquals(0x8000_0000, result.value());
        assertFlags(result, false, true, false, false);
    }

    @Test
    void shiftByZeroPreservesFlags() {
        Flags previous = new Flags(false, true, true, true);
        Alu.Result result = Alu.shl(0x1234_5678, 0, previous);

        assertEquals(0x1234_5678, result.value());
        assertEquals(previous, result.flags());
    }

    @Test
    void shiftCountIsMaskedToFiveBits() {
        Alu.Result result = Alu.shl(1, 32, Flags.CLEAR);

        assertEquals(1, result.value());
        assertEquals(Flags.CLEAR, result.flags());
    }

    @Test
    void shrByZeroPreservesFlags() {
        Flags previous = new Flags(false, true, true, true);
        Alu.Result result = Alu.shr(0x1234_5678, 0, previous);

        assertEquals(0x1234_5678, result.value());
        assertEquals(previous, result.flags());
    }

    @Test
    void shrUpdatesCarryAndZero() {
        Alu.Result result = Alu.shr(1, 1, Flags.CLEAR);

        assertEquals(0, result.value());
        assertFlags(result, true, false, true, false);
    }

    @Test
    void shrDoesNotDuplicateSign() {
        Alu.Result result = Alu.shr(0x8000_0000, 1, Flags.CLEAR);

        assertEquals(0x4000_0000, result.value());
        assertFlags(result, false, false, false, false);
    }

    @Test
    void sarByZeroPreservesFlags() {
        Flags previous = new Flags(false, true, true, true);
        Alu.Result result = Alu.sar(0x1234_5678, 0, previous);

        assertEquals(0x1234_5678, result.value());
        assertEquals(previous, result.flags());
    }

    @Test
    void sarReplicatesSign() {
        Alu.Result result = Alu.sar(0x8000_0000, 1, Flags.CLEAR);

        assertEquals(0xC000_0000, result.value());
        assertFlags(result, false, true, false, false);
    }

    @Test
    void sarSetsCarryFromLastBitShiftedOut() {
        Alu.Result result = Alu.sar(0xFFFF_FFFF, 1, Flags.CLEAR);

        assertEquals(0xFFFF_FFFF, result.value());
        assertFlags(result, false, true, true, false);
    }

    @Test
    void mulUsesLowThirtyTwoBitsAndClearsCarryOverflow() {
        Alu.Result result = Alu.mul(0x7FFF_FFFF, 2);

        assertEquals(0xFFFF_FFFE, result.value());
        assertFlags(result, false, true, false, false);

        Alu.Result zero = Alu.mul(0x1_0000, 0x1_0000);
        assertEquals(0, zero.value());
        assertFlags(zero, true, false, false, false);
    }

    @Test
    void divTruncatesTowardZero() {
        assertEquals(3, Alu.div(7, 2, 0).value());
        assertEquals(-3, Alu.div(-7, 2, 0).value());
        assertEquals(-3, Alu.div(7, -2, 0).value());
        assertEquals(3, Alu.div(-7, -2, 0).value());
    }

    @Test
    void divSetsZeroAndNegativeAndClearsCarryOverflow() {
        Alu.Result zero = Alu.div(0, 5, 0);
        assertEquals(0, zero.value());
        assertFlags(zero, true, false, false, false);

        Alu.Result negative = Alu.div(-3, 2, 0);
        assertEquals(-1, negative.value());
        assertFlags(negative, false, true, false, false);

        Alu.Result negativeResult = Alu.div(3, -2, 0);
        assertEquals(-1, negativeResult.value());
        assertFlags(negativeResult, false, true, false, false);
    }

    @Test
    void divTrapsOnZeroDivisor() {
        MachineFault fault = assertThrows(MachineFault.class, () -> Alu.div(1, 0, 0x10));

        assertEquals(MachineFault.Code.DIVIDE_BY_ZERO, fault.code());
        assertEquals(0x10, fault.pc());
    }

    @Test
    void divTrapsOnMinValueOverflow() {
        MachineFault fault = assertThrows(MachineFault.class,
                () -> Alu.div(Integer.MIN_VALUE, -1, 0x20));

        assertEquals(MachineFault.Code.ARITHMETIC_OVERFLOW, fault.code());
        assertEquals(0x20, fault.pc());
    }

    @Test
    void divuUsesUnsignedValues() {
        Alu.Result result = Alu.divu(0xFFFF_FFFF, 2, 0);

        assertEquals(0x7FFF_FFFF, result.value());
        assertFlags(result, false, false, false, false);
    }

    @Test
    void divuTrapsOnZeroDivisor() {
        MachineFault fault = assertThrows(MachineFault.class, () -> Alu.divu(1, 0, 0));

        assertEquals(MachineFault.Code.DIVIDE_BY_ZERO, fault.code());
    }

    @Test
    void modUsesSignOfDividend() {
        assertEquals(-1, Alu.mod(-7, 2, 0).value());
        assertEquals(1, Alu.mod(7, -2, 0).value());
        assertEquals(1, Alu.mod(7, 2, 0).value());
        assertEquals(-1, Alu.mod(-7, -2, 0).value());
    }

    @Test
    void modSetsZeroAndClearsCarryOverflow() {
        Alu.Result result = Alu.mod(5, 5, 0);

        assertEquals(0, result.value());
        assertFlags(result, true, false, false, false);
    }

    @Test
    void modTrapsOnZeroDivisor() {
        MachineFault fault = assertThrows(MachineFault.class, () -> Alu.mod(1, 0, 0));

        assertEquals(MachineFault.Code.DIVIDE_BY_ZERO, fault.code());
    }

    @Test
    void modTrapsOnMinValueOverflow() {
        MachineFault fault = assertThrows(MachineFault.class,
                () -> Alu.mod(Integer.MIN_VALUE, -1, 0));

        assertEquals(MachineFault.Code.ARITHMETIC_OVERFLOW, fault.code());
    }

    @Test
    void moduUsesUnsignedValues() {
        Alu.Result result = Alu.modu(0xFFFF_FFFF, 2, 0);

        assertEquals(1, result.value());
        assertFlags(result, false, false, false, false);
    }

    @Test
    void moduTrapsOnZeroDivisor() {
        MachineFault fault = assertThrows(MachineFault.class, () -> Alu.modu(1, 0, 0));

        assertEquals(MachineFault.Code.DIVIDE_BY_ZERO, fault.code());
    }

    @Test
    void cmpUsesSubFlagsWithoutWriting() {
        Alu.Result equal = Alu.cmp(5, 5);
        assertEquals(0, equal.value());
        assertFlags(equal, true, false, true, false);

        Alu.Result less = Alu.cmp(1, 2);
        assertEquals(0xFFFF_FFFF, less.value());
        assertFlags(less, false, true, false, false);
    }

    @Test
    void flagsConvertToAndFromBits() {
        Flags all = new Flags(true, true, true, true);

        assertEquals(0x0F, all.bits());
        assertEquals(all, Flags.fromBits(0xFF));
        assertEquals(Flags.CLEAR, Flags.fromBits(0));
        assertTrue(Flags.fromResult(0).zero());
        assertFalse(Flags.fromResult(1).zero());
        assertTrue(Flags.fromResult(-1).negative());
        assertTrue(Flags.fromResult(1, true, true).carry());
        assertTrue(Flags.fromResult(1, true, true).overflow());
    }
}

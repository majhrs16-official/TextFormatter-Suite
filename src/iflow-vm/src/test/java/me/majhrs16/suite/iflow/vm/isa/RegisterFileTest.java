package me.majhrs16.suite.iflow.vm.isa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegisterFileTest {

    @Test
    void defaultConstructorInitializesAllToZero() {
        RegisterFile rf = new RegisterFile();
        for (int i = 0; i < RegisterFile.REG_COUNT; i++) {
            assertEquals(0, rf.get(i), "Register " + i + " should be zero");
        }
        assertFalse(rf.getZ());
        assertFalse(rf.getN());
        assertFalse(rf.getC());
        assertFalse(rf.getV());
    }

    @Test
    void getAndSetGeneralPurposeRegisters() {
        RegisterFile rf = new RegisterFile();
        for (int i = 0; i <= 7; i++) {
            rf.set(i, 0x1234);
            assertEquals(0x1234, rf.get(i), "R" + i);
        }
    }

    @Test
    void getAndSetControlRegisters() {
        RegisterFile rf = new RegisterFile();
        rf.set(RegisterFile.IP, 0xABCD);
        rf.set(RegisterFile.SP, 0x1000);
        rf.set(RegisterFile.FP, 0x2000);
        rf.set(RegisterFile.FL, 0x000F);

        assertEquals(0xABCD, rf.get(RegisterFile.IP));
        assertEquals(0x1000, rf.get(RegisterFile.SP));
        assertEquals(0x2000, rf.get(RegisterFile.FP));
        assertEquals(0x000F, rf.get(RegisterFile.FL));
    }

    @Test
    void valuesAreMaskedTo16Bits() {
        RegisterFile rf = new RegisterFile();
        rf.set(0, 0x12345678);
        assertEquals(0x5678, rf.get(0));

        rf.set(0, -1);
        assertEquals(0xFFFF, rf.get(0));
    }

    @Test
    void invalidRegisterIndexThrows() {
        RegisterFile rf = new RegisterFile();
        assertThrows(IllegalArgumentException.class, () -> rf.get(-1));
        assertThrows(IllegalArgumentException.class, () -> rf.get(RegisterFile.REG_COUNT));
        assertThrows(IllegalArgumentException.class, () -> rf.set(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> rf.set(RegisterFile.REG_COUNT, 0));
    }

    @Test
    void isGprAndIsControl() {
        for (int i = 0; i <= 7; i++) {
            assertTrue(RegisterFile.isGPR(i), "R" + i + " should be GPR");
            assertFalse(RegisterFile.isControl(i), "R" + i + " should not be control");
        }
        for (int i = RegisterFile.IP; i <= RegisterFile.FL; i++) {
            assertTrue(RegisterFile.isControl(i), "Control register " + i + " should be control");
            assertFalse(RegisterFile.isGPR(i), "Control register " + i + " should not be GPR");
        }
    }

    @Test
    void flagOperations() {
        RegisterFile rf = new RegisterFile();

        rf.setZ(true);
        assertTrue(rf.getZ());
        rf.setZ(false);
        assertFalse(rf.getZ());

        rf.setN(true);
        assertTrue(rf.getN());
        rf.setN(false);
        assertFalse(rf.getN());

        rf.setC(true);
        assertTrue(rf.getC());
        rf.setC(false);
        assertFalse(rf.getC());

        rf.setV(true);
        assertTrue(rf.getV());
        rf.setV(false);
        assertFalse(rf.getV());
    }

    @Test
    void getFlagAndSetFlagByIndex() {
        RegisterFile rf = new RegisterFile();
        rf.setFlag(0, true);
        assertTrue(rf.getFlag(0));
        rf.setFlag(0, false);
        assertFalse(rf.getFlag(0));
    }

    @Test
    void clearFlags() {
        RegisterFile rf = new RegisterFile();
        rf.setZ(true);
        rf.setN(true);
        rf.setC(true);
        rf.setV(true);
        rf.clearFlags();
        assertEquals(0, rf.getFlags());
    }

    @Test
    void updateZNSimple() {
        RegisterFile rf = new RegisterFile();
        rf.updateZN(0);
        assertTrue(rf.getZ());
        assertFalse(rf.getN());

        rf.updateZN(0x8000);
        assertFalse(rf.getZ());
        assertTrue(rf.getN());

        rf.updateZN(0x1234);
        assertFalse(rf.getZ());
        assertFalse(rf.getN());
    }

    @Test
    void updateAddFlags() {
        RegisterFile rf = new RegisterFile();

        // 10 + 20 = 30, no carry, no overflow
        rf.updateAddFlags(10, 20, 30, false);
        assertFalse(rf.getZ());
        assertFalse(rf.getN());
        assertFalse(rf.getC());
        assertFalse(rf.getV());

        // 32767 + 1 = -32768 (overflow)
        rf.updateAddFlags(32767, 1, -32768, false);
        assertFalse(rf.getZ());
        assertTrue(rf.getN());
        assertFalse(rf.getC());
        assertTrue(rf.getV());

        // 65535 + 1 = 0 (carry)
        rf.updateAddFlags(65535, 1, 0, false);
        assertTrue(rf.getZ());
        assertFalse(rf.getN());
        assertTrue(rf.getC());
        assertFalse(rf.getV());
    }

    @Test
    void updateSubFlags() {
        RegisterFile rf = new RegisterFile();

        // 20 - 10 = 10, no borrow
        rf.updateAddFlags(20, 10, 10, true);
        assertFalse(rf.getZ());
        assertFalse(rf.getN());
        assertFalse(rf.getC()); // no borrow
        assertFalse(rf.getV());

        // 10 - 20 = -10 (borrow)
        rf.updateAddFlags(10, 20, -10, true);
        assertFalse(rf.getZ());
        assertTrue(rf.getN());
        assertTrue(rf.getC()); // borrow
        assertFalse(rf.getV());

        // -32768 - 1 = 32767 (overflow)
        rf.updateAddFlags(-32768, 1, 32767, true);
        assertFalse(rf.getZ());
        assertFalse(rf.getN());
        assertFalse(rf.getC());
        assertTrue(rf.getV());
    }

    @Test
    void updateMulFlags() {
        RegisterFile rf = new RegisterFile();

        rf.updateMulFlags(10, 20, 200);
        assertFalse(rf.getZ());
        assertFalse(rf.getN());
        assertFalse(rf.getC());
        assertFalse(rf.getV());

        rf.updateMulFlags(300, 300, 90000 & 0xFFFF);
        assertTrue(rf.getC()); // high bits non-zero
    }

    @Test
    void updateDivFlags() {
        RegisterFile rf = new RegisterFile();
        rf.updateDivFlags(5);
        assertFalse(rf.getZ());
        assertFalse(rf.getN());
        assertFalse(rf.getC());
        assertFalse(rf.getV());
    }

    @Test
    void updateShiftFlags() {
        RegisterFile rf = new RegisterFile();

        // 0x8000 << 1 = 0x10000 -> 0 (16-bit wrap), carry=1
        rf.updateShiftFlags(0, 1); // result=0, carry=1
        assertTrue(rf.getZ());
        assertFalse(rf.getN());
        assertTrue(rf.getC());

        // 1 >> 0 = 1, carry=0
        rf.updateShiftFlags(1, 0); // result=1, carry=0
        assertFalse(rf.getZ());
        assertFalse(rf.getN());
        assertFalse(rf.getC());
    }

    @Test
    void updateLogicFlags() {
        RegisterFile rf = new RegisterFile();
        rf.updateLogicFlags(0);
        assertTrue(rf.getZ());
        assertFalse(rf.getN());
        assertFalse(rf.getC());
        assertFalse(rf.getV());

        rf.updateLogicFlags(0x8000);
        assertFalse(rf.getZ());
        assertTrue(rf.getN());
        assertFalse(rf.getC());
        assertFalse(rf.getV());
    }

    @Test
    void updateCmpFlags() {
        RegisterFile rf = new RegisterFile();

        // 10 - 10 = 0
        rf.updateCmpFlags(10, 10);
        assertTrue(rf.getZ());
        assertFalse(rf.getN());
        assertFalse(rf.getC());
        assertFalse(rf.getV());

        // 10 - 20 = -10
        rf.updateCmpFlags(10, 20);
        assertFalse(rf.getZ());
        assertTrue(rf.getN());
        assertTrue(rf.getC()); // borrow

        // -10 - (-20) = 10
        rf.updateCmpFlags(-10, -20);
        assertFalse(rf.getZ());
        assertFalse(rf.getN());
        assertFalse(rf.getC()); // no borrow
        assertFalse(rf.getV());
    }

    @Test
    void updateCmpuFlags() {
        RegisterFile rf = new RegisterFile();

        rf.updateCmpuFlags(10, 20); // unsigned 10 < 20
        assertTrue(rf.getC());

        rf.updateCmpuFlags(20, 10); // unsigned 20 > 10
        assertFalse(rf.getC());
    }

    @Test
    void flagsRegisterAccess() {
        RegisterFile rf = new RegisterFile();
        rf.setFlags(0x000F);
        assertEquals(0x000F, rf.getFlags());
    }

    @Test
    void toStringContainsAllRegisters() {
        RegisterFile rf = new RegisterFile();
        rf.set(0, 0x1234);
        rf.set(RegisterFile.IP, 0x5678);
        String str = rf.toString();
        assertTrue(str.contains("R0=0x1234"));
        assertTrue(str.contains("IP=0x5678"));
        assertTrue(str.contains("SP=0x0000"));
    }
}
package me.majhrs16.suite.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemVerTest {

    @Test
    void parsesAndOrders() {
        assertEquals(0, SemVer.parse("2.1.0").compareTo(SemVer.of(2, 1, 0)));
        assertTrue(SemVer.parse("2.1.0").compareTo(SemVer.parse("2.0.9")) > 0);
        assertTrue(SemVer.parse("1.9.9").compareTo(SemVer.parse("2.0.0")) < 0);
    }

    @Test
    void preReleaseIsLowerThanRelease() {
        assertTrue(SemVer.parse("2.1.0-alpha").compareTo(SemVer.parse("2.1.0")) < 0);
        assertTrue(SemVer.parse("2.1.0-alpha").compareTo(SemVer.parse("2.1.0-beta")) < 0);
    }

    @Test
    void numericIdentifiersCompareNumerically() {
        assertTrue(SemVer.parse("2.1.0-1").compareTo(SemVer.parse("2.1.0-9")) < 0);
        assertTrue(SemVer.parse("2.1.0-10").compareTo(SemVer.parse("2.1.0-9")) > 0);
    }

    @Test
    void rejectsInvalid() {
        assertFalse(SemVer.isValid("2"));
        assertFalse(SemVer.isValid("2.1"));
        assertFalse(SemVer.isValid("none"));
        assertFalse(SemVer.isValid(""));
        assertFalse(SemVer.isValid(null));
    }

    @Test
    void isAtLeast() {
        assertTrue(SemVer.parse("21.0.0").isAtLeast(21, 0, 0));
        assertTrue(SemVer.parse("21.0.0").isAtLeast(17, 0, 0));
        assertFalse(SemVer.parse("17.0.0").isAtLeast(21, 0, 0));
    }
}
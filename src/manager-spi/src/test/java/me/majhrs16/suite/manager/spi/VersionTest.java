package me.majhrs16.suite.manager.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionTest {

    @Test
    void parse_validSemver() {
        Version v = Version.parse("1.2.3");
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
        assertEquals(3, v.patch());
    }

    @Test
    void parse_withPrerelease() {
        Version v = Version.parse("1.2.3-beta");
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
        assertEquals(3, v.patch());
        assertEquals("beta", v.prerelease());
    }

    @Test
    void parse_withBuild() {
        Version v = Version.parse("1.2.3+build123");
        assertEquals("build123", v.build());
    }

    @Test
    void parse_invalid_throws() {
        assertThrows(IllegalArgumentException.class, () -> Version.parse("not-a-version"));
        assertThrows(IllegalArgumentException.class, () -> Version.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("1.2"));
    }

    @Test
    void compareTo_numeric() {
        assertTrue(Version.parse("2.0.0").compareTo(Version.parse("1.9.9")) > 0);
        assertTrue(Version.parse("1.10.0").compareTo(Version.parse("1.9.9")) > 0);
        assertTrue(Version.parse("1.2.3").compareTo(Version.parse("1.2.3")) == 0);
        assertTrue(Version.parse("1.2.3").compareTo(Version.parse("1.2.4")) < 0);
    }

    @Test
    void compareTo_prerelease() {
        // Release > prerelease
        assertTrue(Version.parse("1.0.0").compareTo(Version.parse("1.0.0-beta")) > 0);
        // prerelease ordering
        assertTrue(Version.parse("1.0.0-beta.2").compareTo(Version.parse("1.0.0-beta.1")) > 0);
        assertTrue(Version.parse("1.0.0-rc.1").compareTo(Version.parse("1.0.0-beta.9")) > 0);
    }

    @Test
    void satisfies_exact() {
        Version v = Version.parse("1.2.3");
        assertTrue(v.satisfies("1.2.3"));
        assertFalse(v.satisfies("1.2.4"));
    }

    @Test
    void satisfies_caret() {
        Version v = Version.parse("1.2.3");
        assertTrue(v.satisfies("^1.2.3"));
        assertTrue(v.satisfies("^1.0.0"));
        assertFalse(v.satisfies("^2.0.0"));
        
        // 0.x.y special case
        Version v0 = Version.parse("0.2.3");
        assertTrue(v0.satisfies("^0.2.3"));
        assertFalse(v0.satisfies("^0.3.0"));
    }

    @Test
    void satisfies_tilde() {
        Version v = Version.parse("1.2.3");
        assertTrue(v.satisfies("~1.2.3"));
        assertTrue(v.satisfies("~1.2.0"));
        assertFalse(v.satisfies("~1.3.0"));
    }

    @Test
    void satisfies_range() {
        Version v = Version.parse("1.5.0");
        assertTrue(v.satisfies("1.0.0 - 2.0.0"));
        assertFalse(v.satisfies("2.0.0 - 3.0.0"));
    }

    @Test
    void satisfies_operators() {
        Version v = Version.parse("1.5.0");
        assertTrue(v.satisfies(">=1.0.0"));
        assertTrue(v.satisfies("<=2.0.0"));
        assertTrue(v.satisfies(">1.0.0"));
        assertTrue(v.satisfies("<2.0.0"));
        assertTrue(v.satisfies("!=2.0.0"));
        assertFalse(v.satisfies("!=1.5.0"));
    }

    @Test
    void satisfies_or() {
        Version v = Version.parse("1.5.0");
        assertTrue(v.satisfies("2.0.0 || 1.5.0"));
        assertTrue(v.satisfies("1.5.0 || 2.0.0"));
        assertFalse(v.satisfies("2.0.0 || 3.0.0"));
    }

    @Test
    void satisfies_wildcard() {
        Version v = Version.parse("1.2.3");
        assertTrue(v.satisfies("1.2.*"));
        assertTrue(v.satisfies("1.*.*"));
        
        Version v0 = Version.parse("0.5.0");
        assertTrue(v0.satisfies("0.5.*"));
    }

    @Test
    void satisfies_emptyOrNull_returnsTrue() {
        Version v = Version.parse("1.2.3");
        assertTrue(v.satisfies(""));
        assertTrue(v.satisfies("   "));
        assertTrue(v.satisfies(null));
    }

    @Test
    void tryParse_invalid_returnsNull() {
        assertNull(Version.tryParse("not-a-version"));
        assertNull(Version.tryParse(""));
    }
}
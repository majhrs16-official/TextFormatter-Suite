package me.majhrs16.suite.manager.spi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlatformCompatibilityTest {

    @Test
    void commonAndAnyMatchEveryHost() {
        assertTrue(PlatformCompatibility.matches("common", "spigot"));
        assertTrue(PlatformCompatibility.matches("any", "fabric"));
        assertTrue(PlatformCompatibility.matches("", "spigot"));
        assertTrue(PlatformCompatibility.matches(null, "fabric"));
    }

    @Test
    void bukkitFamilyIsEquivalentToSpigot() {
        assertTrue(PlatformCompatibility.matches("bukkit", "spigot"));
        assertTrue(PlatformCompatibility.matches("paper", "spigot"));
        assertTrue(PlatformCompatibility.matches("purpur", "spigot"));
        assertTrue(PlatformCompatibility.matches("spigot", "paper"));
    }

    @Test
    void differentPlatformsDoNotMatch() {
        assertFalse(PlatformCompatibility.matches("fabric", "spigot"));
        assertFalse(PlatformCompatibility.matches("spigot", "fabric"));
        assertFalse(PlatformCompatibility.matches("velocity", "spigot"));
    }
}

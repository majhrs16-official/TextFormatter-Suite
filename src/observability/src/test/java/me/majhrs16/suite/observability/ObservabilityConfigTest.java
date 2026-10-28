package me.majhrs16.suite.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObservabilityConfigTest {

    @TempDir
    Path dir;

    @Test
    void missingFileUsesEnabledDefaults() {
        ObservabilityConfig config = ObservabilityConfig.loadFromConfigDir(dir);

        assertTrue(config.enabled());
        assertTrue(config.usable());
        assertEquals(ObservabilityConfig.DEFAULT_PORT, config.metricsPort());
        assertEquals(ObservabilityConfig.DEFAULT_BIND, config.metricsBind());
    }

    @Test
    void readsModuleConfigDirectory() throws Exception {
        Path configDir = dir.resolve("config/observability");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yml"), """
            enabled: true
            metricsPort: 0
            metricsBind: 127.0.0.1
            """);

        ObservabilityConfig config = ObservabilityConfig.loadFromConfigDir(configDir);

        assertTrue(config.usable());
        assertEquals(0, config.metricsPort());
    }

    @Test
    void disabledConfigIsNotUsable() throws Exception {
        Path configDir = dir.resolve("config/observability");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yml"), "enabled: false\nmetricsPort: 9090\n");

        assertFalse(ObservabilityConfig.loadFromConfigDir(configDir).usable());
    }

    @Test
    void invalidPortIsNotUsable() throws Exception {
        Path configDir = dir.resolve("config/observability");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yml"), "enabled: true\nmetricsPort: 70000\n");

        assertFalse(ObservabilityConfig.loadFromConfigDir(configDir).usable());
    }
}

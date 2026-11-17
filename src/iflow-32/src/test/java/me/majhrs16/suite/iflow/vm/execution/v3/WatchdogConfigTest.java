package me.majhrs16.suite.iflow.vm.execution.v3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import me.majhrs16.suite.api.config.ConfigVersionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchdogConfigTest {

    @TempDir
    Path configDir;

    @Test
    void missingFileKeepsTheWatchdogEnabled() {
        assertTrue(WatchdogConfig.load(configDir, null).enabled());
    }

    @Test
    void loadsAnExplicitDisable() throws IOException {
        write("""
                config-version: 1.0
                watchdog:
                  enabled: false
                """);

        assertFalse(WatchdogConfig.load(configDir, null).enabled());
    }

    @Test
    void missingWatchdogSectionKeepsTheDefault() throws IOException {
        write("config-version: 1.0\n");

        assertTrue(WatchdogConfig.load(configDir, null).enabled());
    }

    @Test
    void rejectsAnInvalidBooleanInsteadOfGuessing() throws IOException {
        write("""
                config-version: 1.0
                watchdog:
                  enabled: maybe
                """);

        assertThrows(IllegalArgumentException.class,
                () -> WatchdogConfig.load(configDir, null));
    }

    @Test
    void rejectsAMissingConfigVersion() throws IOException {
        write("watchdog:\n  enabled: false\n");

        assertThrows(ConfigVersionException.class,
                () -> WatchdogConfig.load(configDir, null));
    }

    private void write(String yaml) throws IOException {
        Files.writeString(configDir.resolve("config.yml"), yaml);
    }
}

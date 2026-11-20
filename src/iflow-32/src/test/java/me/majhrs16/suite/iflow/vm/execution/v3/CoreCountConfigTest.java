package me.majhrs16.suite.iflow.vm.execution.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import me.majhrs16.suite.api.config.ConfigVersionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreCountConfigTest {

    @TempDir
    Path configDir;

    @Test
    void missingFileUsesOneCore() {
        assertEquals(1, CoreCountConfig.load(configDir).coreCount());
    }

    @Test
    void missingKeyUsesOneCore() throws IOException {
        write("config-version: 1.0\n");

        assertEquals(1, CoreCountConfig.load(configDir).coreCount());
    }

    @Test
    void loadsAnExplicitSingleCore() throws IOException {
        write("""
                config-version: 1.0
                core-count: 1
                """);

        assertEquals(1, CoreCountConfig.load(configDir).coreCount());
    }

    @Test
    void rejectsCoreCountsOutsideTheArchitecturalRange() throws IOException {
        write("config-version: 1.0\ncore-count: 0\n");
        assertThrows(IllegalArgumentException.class,
                () -> CoreCountConfig.load(configDir));

        write("config-version: 1.0\ncore-count: 65\n");
        assertThrows(IllegalArgumentException.class,
                () -> CoreCountConfig.load(configDir));

        write("config-version: 1.0\ncore-count: 4294967297\n");
        assertThrows(IllegalArgumentException.class,
                () -> CoreCountConfig.load(configDir));
    }

    @Test
    void rejectsMulticoreUntilV32IsComplete() throws IOException {
        write("config-version: 1.0\ncore-count: 2\n");

        assertThrows(UnsupportedOperationException.class,
                () -> CoreCountConfig.load(configDir));
    }

    @Test
    void rejectsANonIntegerValue() throws IOException {
        write("config-version: 1.0\ncore-count: many\n");

        assertThrows(IllegalArgumentException.class,
                () -> CoreCountConfig.load(configDir));
    }

    @Test
    void rejectsAMissingConfigVersion() throws IOException {
        write("core-count: 1\n");

        assertThrows(ConfigVersionException.class,
                () -> CoreCountConfig.load(configDir));
    }

    private void write(String yaml) throws IOException {
        Files.writeString(configDir.resolve("config.yml"), yaml);
    }
}

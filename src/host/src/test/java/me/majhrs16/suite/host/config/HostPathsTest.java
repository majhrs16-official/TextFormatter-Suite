package me.majhrs16.suite.host.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostPathsTest {

    @TempDir
    Path dir;

    @Test
    void canonicalSuiteDirLivesUnderConfig() {
        assertEquals(dir.resolve("config"), HostPaths.configRoot(dir));
        assertEquals(dir.resolve("config/suite"), HostPaths.suiteDir(dir));
        assertEquals(dir.resolve("config/suite/config.yml"), HostPaths.canonicalSuiteConfig(dir));
        assertEquals(dir.resolve("config/suite/messages.yml"), HostPaths.canonicalSuiteMessages(dir));
    }

    @Test
    void canonicalConfigWinsWhenBothExist() throws Exception {
        Files.createDirectories(dir.resolve("config/suite"));
        Files.writeString(dir.resolve("config/suite/config.yml"), "quick-look: true\n");
        Files.writeString(dir.resolve("config.yml"), "quick-look: false\n");

        assertEquals(dir.resolve("config/suite/config.yml"), HostPaths.suiteConfig(dir));
    }

    @Test
    void legacyConfigIsUsedDuringMigration() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "quick-look: false\n");
        Files.writeString(dir.resolve("messages.yml"), "prefix: legacy\n");

        assertEquals(dir.resolve("config.yml"), HostPaths.suiteConfig(dir));
        assertEquals(dir.resolve("messages.yml"), HostPaths.suiteMessages(dir));
    }

    @Test
    void missingFilesFallBackToCanonicalDestination() {
        assertEquals(dir.resolve("config/suite/config.yml"), HostPaths.suiteConfig(dir));
        assertEquals(dir.resolve("config/suite/messages.yml"), HostPaths.suiteMessages(dir));
    }
}

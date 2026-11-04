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
        assertEquals(dir.resolve("config/suite/commands.yml"), HostPaths.canonicalSuiteCommands(dir));
    }

    @Test
    void moduleConfigLivesUnderConfigManager() {
        assertEquals(dir.resolve("config/manager"), HostPaths.moduleConfigRoot(dir));
        assertEquals(dir.resolve("config/manager/textformatter"),
                HostPaths.moduleConfigDir(dir, "textformatter"));
        assertEquals(dir.resolve("config/manager/iflow-firewall"),
                HostPaths.moduleConfigDir(dir, "iflow-firewall"));
    }

    @Test
    void canonicalConfigWinsWhenBothExist() throws Exception {
        Files.createDirectories(dir.resolve("config/suite"));
        Files.writeString(dir.resolve("config/suite/config.yml"), "quick-look: true\n");
        Files.writeString(dir.resolve("config.yml"), "quick-look: false\n");
        Files.writeString(dir.resolve("config/suite/commands.yml"), "root: [suite]\n");
        Files.writeString(dir.resolve("commands.yml"), "root: [legacy]\n");

        assertEquals(dir.resolve("config/suite/config.yml"), HostPaths.suiteConfig(dir));
        assertEquals(dir.resolve("config/suite/commands.yml"), HostPaths.suiteCommands(dir));
    }

    @Test
    void legacyConfigIsUsedDuringMigration() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "quick-look: false\n");
        Files.writeString(dir.resolve("messages.yml"), "prefix: legacy\n");
        Files.writeString(dir.resolve("commands.yml"), "root: [legacy]\n");

        assertEquals(dir.resolve("config.yml"), HostPaths.suiteConfig(dir));
        assertEquals(dir.resolve("messages.yml"), HostPaths.suiteMessages(dir));
        assertEquals(dir.resolve("commands.yml"), HostPaths.suiteCommands(dir));
    }

    @Test
    void missingFilesFallBackToCanonicalDestination() {
        assertEquals(dir.resolve("config/suite/config.yml"), HostPaths.suiteConfig(dir));
        assertEquals(dir.resolve("config/suite/messages.yml"), HostPaths.suiteMessages(dir));
        assertEquals(dir.resolve("config/suite/commands.yml"), HostPaths.suiteCommands(dir));
    }
}

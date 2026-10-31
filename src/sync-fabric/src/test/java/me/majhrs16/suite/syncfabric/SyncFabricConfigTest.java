package me.majhrs16.suite.syncfabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncFabricConfigTest {

    @TempDir
    Path configDir;

    @Test
    void missingFileUsesSafeDefaults() {
        SyncFabricConfig config = SyncFabricConfig.loadFromConfigDir(configDir);

        assertTrue(config.enabled());
        assertTrue(config.logChatToConsole());
    }

    @Test
    void readsValuesFromConfig() throws Exception {
        Files.writeString(configDir.resolve("config.yml"), """
                enabled: false
                log-chat-to-console: false
                """);

        SyncFabricConfig config = SyncFabricConfig.loadFromConfigDir(configDir);

        assertFalse(config.enabled());
        assertFalse(config.logChatToConsole());
    }
}

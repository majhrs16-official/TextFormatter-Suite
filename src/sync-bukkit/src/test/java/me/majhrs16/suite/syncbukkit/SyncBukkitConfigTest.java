package me.majhrs16.suite.syncbukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncBukkitConfigTest {

    @TempDir
    Path configDir;

    @Test
    void missingFileUsesSafeDefaults() {
        SyncBukkitConfig config = SyncBukkitConfig.loadFromConfigDir(configDir);

        assertTrue(config.enabled());
        assertEquals(SyncBukkitConfig.ClaimMode.CANCEL_EVENT, config.claimMode());
        assertTrue(config.logChatToConsole());
    }

    @Test
    void readsClaimModeAndConsoleFlag() throws Exception {
        Files.writeString(configDir.resolve("config.yml"), """
                enabled: false
                claim-mode: clear-recipients
                log-chat-to-console: false
                config-version: 1.0
                """);

        SyncBukkitConfig config = SyncBukkitConfig.loadFromConfigDir(configDir);

        assertFalse(config.enabled());
        assertEquals(SyncBukkitConfig.ClaimMode.CLEAR_RECIPIENTS, config.claimMode());
        assertFalse(config.logChatToConsole());
    }

    @Test
    void unknownClaimModeFallsBackToCancelEvent() {
        assertEquals(SyncBukkitConfig.ClaimMode.CANCEL_EVENT,
                SyncBukkitConfig.ClaimMode.parse("not-a-mode"));
    }
}

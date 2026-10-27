package me.majhrs16.suite.synctelegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelegramConfigTest {

    @TempDir
    Path dir;

    private Path canonical() throws Exception {
        Path file = dir.resolve("config/sync-telegram/config.yml");
        Files.createDirectories(file.getParent());
        return file;
    }

    @Test
    void missingFileIsNotUsable() {
        TelegramConfig config = TelegramConfig.load(dir);

        assertFalse(config.enabled());
        assertFalse(config.usable());
        assertEquals(0L, config.chatId());
    }

    @Test
    void readsCanonicalFile() throws Exception {
        Files.writeString(canonical(), """
            enabled: true
            token: bot-token
            chatId: -1001234567890
            """);

        TelegramConfig config = TelegramConfig.load(dir);

        assertTrue(config.usable());
        assertEquals("bot-token", config.token());
        assertEquals(-1001234567890L, config.chatId());
    }

    @Test
    void enabledWithoutChatIdIsNotUsable() throws Exception {
        Files.writeString(canonical(), "enabled: true\ntoken: bot-token\n");

        assertFalse(TelegramConfig.load(dir).usable());
    }

    @Test
    void enabledWithoutTokenIsNotUsable() throws Exception {
        Files.writeString(canonical(), "enabled: true\nchatId: 42\n");

        assertFalse(TelegramConfig.load(dir).usable());
    }

    @Test
    void legacyFileIsReadDuringMigration() throws Exception {
        Path legacy = dir.resolve("sync/telegram.yml");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "enabled: true\ntoken: viejo\nchatId: 7\n");

        TelegramConfig config = TelegramConfig.load(dir);

        assertTrue(config.usable());
        assertEquals(7L, config.chatId());
    }
}

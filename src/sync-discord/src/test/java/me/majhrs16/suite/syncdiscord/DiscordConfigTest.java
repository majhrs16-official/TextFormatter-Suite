package me.majhrs16.suite.syncdiscord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscordConfigTest {

    @TempDir
    Path dir;

    private Path canonical() throws Exception {
        Path file = dir.resolve("config/sync-discord/config.yml");
        Files.createDirectories(file.getParent());
        return file;
    }

    @Test
    void missingFileIsNotUsable() {
        DiscordConfig config = DiscordConfig.load(dir);

        assertFalse(config.enabled());
        assertFalse(config.usable());
        assertFalse(config.hasChannel());
    }

    @Test
    void readsCanonicalFile() throws Exception {
        Files.writeString(canonical(), """
            enabled: true
            token: discord-token
            channel: 12345
            """);

        DiscordConfig config = DiscordConfig.load(dir);

        assertTrue(config.usable());
        assertTrue(config.hasChannel());
        assertEquals("discord-token", config.token());
        assertEquals(12345L, config.channelId());
        assertEquals(DiscordConfig.DEFAULT_FORMAT, config.format());
    }

    @Test
    void enabledWithoutTokenIsNotUsable() throws Exception {
        Files.writeString(canonical(), "enabled: true\nchannel: 1\n");

        assertFalse(DiscordConfig.load(dir).usable());
    }

    @Test
    void missingChannelStillAllowsConnection() throws Exception {
        Files.writeString(canonical(), "enabled: true\ntoken: t\n");

        DiscordConfig config = DiscordConfig.load(dir);

        assertTrue(config.usable());
        assertFalse(config.hasChannel());
        assertEquals(0L, config.channelId());
    }

    @Test
    void legacyFileIsReadDuringMigration() throws Exception {
        Path legacy = dir.resolve("sync/discord.yml");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "enabled: true\ntoken: viejo\nchannel: 9\n");

        DiscordConfig config = DiscordConfig.load(dir);

        assertTrue(config.usable());
        assertEquals(9L, config.channelId());
        assertEquals(DiscordConfig.DEFAULT_FORMAT, config.format());
    }
}

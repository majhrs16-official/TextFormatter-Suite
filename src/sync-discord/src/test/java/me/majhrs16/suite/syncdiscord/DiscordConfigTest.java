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
            config-version: 1.0
            """);

        DiscordConfig config = DiscordConfig.load(dir);

        assertTrue(config.usable());
        assertTrue(config.hasChannel());
        assertEquals("discord-token", config.token());
        assertEquals(12345L, config.channelId());
        assertEquals(DiscordConfig.DEFAULT_FORMAT, config.format());
        assertEquals(DiscordConfig.DEFAULT_TRANSPORT, config.transport());
    }

    @Test
    void readsTheSelectedTransportAdapter() throws Exception {
        Files.writeString(canonical(), """
            enabled: true
            token: discord-token
            channel: 12345
            transport: jdk
            config-version: 1.0
            """);

        assertEquals("jdk", DiscordConfig.load(dir).transport());
    }

    @Test
    void enabledWithoutTokenIsNotUsable() throws Exception {
        Files.writeString(canonical(), "enabled: true\nchannel: 1\nconfig-version: 1.0\n");

        assertFalse(DiscordConfig.load(dir).usable());
    }

    @Test
    void missingChannelStillAllowsConnection() throws Exception {
        Files.writeString(canonical(), "enabled: true\ntoken: t\nconfig-version: 1.0\n");

        DiscordConfig config = DiscordConfig.load(dir);

        assertTrue(config.usable());
        assertFalse(config.hasChannel());
        assertEquals(0L, config.channelId());
    }

    @Test
    void legacyFileIsIgnored() throws Exception {
        Path legacy = dir.resolve("sync/discord.yml");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy,
            "enabled: true\ntoken: viejo\nchannel: 9\nconfig-version: 1.0\n");

        DiscordConfig config = DiscordConfig.load(dir);

        assertFalse(config.usable());
        assertEquals(0L, config.channelId());
    }
}

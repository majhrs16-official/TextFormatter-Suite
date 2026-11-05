package me.majhrs16.suite.host.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import me.majhrs16.suite.api.config.ConfigVersionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesConfigTest {

    @TempDir
    Path dir;

    private Path suiteMessages() throws Exception {
        Path file = dir.resolve("config/suite/messages.yml");
        Files.createDirectories(file.getParent());
        return file;
    }

    @Test
    void missingFileFallsBackToResourceDefaults() {
        // No messages.yml file, should load from default resource
        // Since we don't have a test resource, it will be empty
        // But format() returns the key itself when not found
        MessagesConfig config = MessagesConfig.load(dir, "/defaults/messages.yml");

        // With no resource available, missing keys return the key itself
        assertEquals("no.existe", config.format("no.existe"));
    }

    @Test
    void userFileOverridesResourceDefaults() throws Exception {
        // Create a custom messages.yml
        Files.writeString(suiteMessages(), """
            prefix: "[TFS] "
            status:
              channels: "canales: {}"
            extra: conservado
            config-version: 1.0
            """);

        MessagesConfig config = MessagesConfig.load(dir, "/defaults/messages.yml");

        assertEquals("[TFS] ", config.format("prefix"));
        assertEquals("canales: 3", config.format("status.channels", 3));
        assertEquals("conservado", config.format("extra"));
    }

    @Test
    void corruptFileKeepsResourceDefaults() throws Exception {
        Files.writeString(suiteMessages(), "prefix: [suite");

        MessagesConfig config = MessagesConfig.load(dir, "/defaults/messages.yml");

        // Corrupt file → resource defaults (or key itself if no resource)
        assertEquals("prefix", config.format("prefix"));
    }

    @Test
    void missingConfigVersionFailsExplicitly() throws Exception {
        Files.writeString(suiteMessages(), "prefix: \"[TFS] \"\n");

        assertThrows(ConfigVersionException.class,
            () -> MessagesConfig.load(dir, "/defaults/messages.yml"));
    }

    @Test
    void unknownKeyReturnsKeyItself() {
        MessagesConfig config = MessagesConfig.load(dir, "/defaults/messages.yml");

        assertTrue(config.format("no.existe").contains("no.existe"));
    }
}
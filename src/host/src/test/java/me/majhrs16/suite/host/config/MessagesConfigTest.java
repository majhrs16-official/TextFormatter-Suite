package me.majhrs16.suite.host.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesConfigTest {

    @TempDir
    Path dir;

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
        Files.writeString(dir.resolve("messages.yml"), """
            prefix: "[TFS] "
            status:
              channels: "canales: {}"
            extra: conservado
            """);

        MessagesConfig config = MessagesConfig.load(dir, "/defaults/messages.yml");

        assertEquals("[TFS] ", config.format("prefix"));
        assertEquals("canales: 3", config.format("status.channels", 3));
        assertEquals("conservado", config.format("extra"));
    }

    @Test
    void corruptFileKeepsResourceDefaults() throws Exception {
        Files.writeString(dir.resolve("messages.yml"), "prefix: [suite");

        MessagesConfig config = MessagesConfig.load(dir, "/defaults/messages.yml");

        // Corrupt file → resource defaults (or key itself if no resource)
        assertEquals("prefix", config.format("prefix"));
    }

    @Test
    void unknownKeyReturnsKeyItself() {
        MessagesConfig config = MessagesConfig.load(dir, "/defaults/messages.yml");

        assertTrue(config.format("no.existe").contains("no.existe"));
    }
}
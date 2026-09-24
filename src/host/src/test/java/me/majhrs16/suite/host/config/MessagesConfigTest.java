package me.majhrs16.suite.host.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesConfigTest {

    @TempDir
    Path dir;

    private static final Map<String, String> DEFAULTS = Map.of(
        "prefix", "[suite] ",
        "status.channels", "[suite] canales: {}");

    @Test
    void missingFileFallsBackToBuiltIns() {
        MessagesConfig config = MessagesConfig.load(dir, DEFAULTS);

        assertEquals("[suite] canales: [a, b]", config.format("status.channels", "[a, b]"));
    }

    @Test
    void nestedYamlFlattensToDottedKeysAndOverridesDefaults() throws Exception {
        Files.writeString(dir.resolve("messages.yml"), """
            prefix: "[TFS] "
            status:
              channels: "canales: {}"
            extra: conservado
            """);

        MessagesConfig config = MessagesConfig.load(dir, DEFAULTS);

        assertEquals("[TFS] ", config.format("prefix"));
        assertEquals("canales: 3", config.format("status.channels", 3));
        assertEquals("conservado", config.format("extra"));
    }

    @Test
    void corruptFileKeepsDefaults() throws Exception {
        Files.writeString(dir.resolve("messages.yml"), "prefix: [suite");

        MessagesConfig config = MessagesConfig.load(dir, DEFAULTS);

        assertEquals("[suite] ", config.format("prefix"));
    }

    @Test
    void unknownKeyFallsBackToTheKeyItself() {
        MessagesConfig config = MessagesConfig.load(dir, DEFAULTS);

        assertTrue(config.format("no.existe").contains("no.existe"));
    }
}

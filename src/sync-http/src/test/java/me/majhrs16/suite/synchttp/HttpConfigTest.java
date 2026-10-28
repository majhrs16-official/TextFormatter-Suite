package me.majhrs16.suite.synchttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpConfigTest {

    @TempDir
    Path dir;

    @Test
    void missingFileIsNotUsable() {
        HttpConfig config = HttpConfig.loadFromConfigDir(dir);

        assertFalse(config.enabled());
        assertFalse(config.usable());
        assertEquals(HttpConfig.DEFAULT_PATH, config.inboundPath());
        assertEquals(HttpConfig.DEFAULT_BIND, config.bind());
    }

    @Test
    void readsCanonicalModuleFile() throws Exception {
        Path configDir = dir.resolve("config/sync-http");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yml"), """
            enabled: true
            outboundUrl: "https://example.test/hook"
            inboundPort: 0
            inboundPath: "/events"
            bind: "127.0.0.1"
            authToken: "secret"
            hmacSecret: "hmac"
            """);

        HttpConfig config = HttpConfig.loadFromConfigDir(configDir);

        assertTrue(config.usable());
        assertEquals("https://example.test/hook", config.outboundUrl());
        assertEquals(0, config.inboundPort());
        assertEquals("/events", config.inboundPath());
        assertEquals("secret", config.authToken());
        assertEquals("hmac", config.hmacSecret());
    }

    @Test
    void enabledWithoutOutboundUrlIsNotUsable() throws Exception {
        Path configDir = dir.resolve("config/sync-http");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yml"), "enabled: true\ninboundPort: 8080\n");

        assertFalse(HttpConfig.loadFromConfigDir(configDir).usable());
    }

    @Test
    void invalidPortIsNotUsable() throws Exception {
        Path configDir = dir.resolve("config/sync-http");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yml"), """
            enabled: true
            outboundUrl: "https://example.test/hook"
            inboundPort: 70000
            """);

        assertFalse(HttpConfig.loadFromConfigDir(configDir).usable());
    }
}

package me.majhrs16.suite.syncwebsocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebSocketSyncConfigTest {

    @TempDir
    Path dir;

    private Path canonical() throws Exception {
        Path file = dir.resolve("config/sync-websocket/config.yml");
        Files.createDirectories(file.getParent());
        return file;
    }

    @Test
    void missingFileUsesDisabledDefaults() {
        WebSocketSyncConfig config = WebSocketSyncConfig.load(dir);

        assertFalse(config.enabled());
        assertFalse(config.usable());
        assertEquals(WebSocketSyncConfig.DEFAULT_PORT, config.port());
        assertEquals(WebSocketSyncConfig.DEFAULT_BIND, config.bind());
        assertEquals("", config.token());
    }

    @Test
    void readsCanonicalFile() throws Exception {
        Files.writeString(canonical(), """
            enabled: true
            token: secreto
            port: 9099
            bind: 0.0.0.0
            """);

        WebSocketSyncConfig config = WebSocketSyncConfig.load(dir);

        assertTrue(config.usable());
        assertEquals("secreto", config.token());
        assertEquals(9099, config.port());
        assertEquals("0.0.0.0", config.bind());
    }

    @Test
    void enabledWithoutTokenIsNotUsable() throws Exception {
        Files.writeString(canonical(), "enabled: true\n");

        assertFalse(WebSocketSyncConfig.load(dir).usable());
    }

    @Test
    void invalidPortFallsBackToDefault() throws Exception {
        Files.writeString(canonical(), "enabled: true\ntoken: t\nport: 70000\n");

        assertEquals(WebSocketSyncConfig.DEFAULT_PORT, WebSocketSyncConfig.load(dir).port());
    }

    @Test
    void readsFromTheModuleConfigDirectory() throws Exception {
        Path configDir = dir.resolve("config/sync-websocket");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yml"), """
            enabled: true
            token: module-token
            port: 9098
            bind: 127.0.0.1
            """);

        WebSocketSyncConfig config = WebSocketSyncConfig.loadFromConfigDir(configDir);

        assertTrue(config.usable());
        assertEquals("module-token", config.token());
        assertEquals(9098, config.port());
    }

    @Test
    void legacyFileIsReadDuringMigration() throws Exception {
        Path legacy = dir.resolve("sync/websocket.yml");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "enabled: true\ntoken: viejo\nport: 9080\n");

        WebSocketSyncConfig config = WebSocketSyncConfig.load(dir);

        assertTrue(config.usable());
        assertEquals(9080, config.port());
    }
}

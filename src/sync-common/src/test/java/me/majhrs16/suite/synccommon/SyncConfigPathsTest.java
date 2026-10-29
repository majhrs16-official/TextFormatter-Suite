package me.majhrs16.suite.synccommon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncConfigPathsTest {

    @TempDir
    Path dir;

    @Test
    void canonicalPathsLiveUnderConfigSyncModules() {
        assertEquals(dir.resolve("config/sync-bus/config.yml"), SyncConfigPaths.canonicalBus(dir));
        assertEquals(dir.resolve("config/sync-discord/config.yml"), SyncConfigPaths.canonicalDiscord(dir));
        assertEquals(dir.resolve("config/sync-websocket/config.yml"), SyncConfigPaths.canonicalWebSocket(dir));
        assertEquals(dir.resolve("config/sync-telegram/config.yml"), SyncConfigPaths.canonicalTelegram(dir));
        assertEquals(dir.resolve("config/sync-http/config.yml"), SyncConfigPaths.canonicalHttp(dir));
        assertEquals(dir.resolve("config/sync-velocity-core/config.yml"), SyncConfigPaths.canonicalVelocity(dir));
        assertEquals(dir.resolve("config/sync-socket/tcp.yml"), SyncConfigPaths.canonicalTcp(dir));
        assertEquals(dir.resolve("config/sync-socket/udp.yml"), SyncConfigPaths.canonicalUdp(dir));
    }

    @Test
    void canonicalFilesWinOverLegacy() throws Exception {
        Files.createDirectories(dir.resolve("config/sync-discord"));
        Files.createDirectories(dir.resolve("sync"));
        Files.writeString(dir.resolve("config/sync-discord/config.yml"), "enabled: true\n");
        Files.writeString(dir.resolve("sync/discord.yml"), "enabled: false\n");

        assertEquals(dir.resolve("config/sync-discord/config.yml"), SyncConfigPaths.discord(dir));
    }

    @Test
    void legacyLayoutIsReadDuringMigration() throws Exception {
        Files.createDirectories(dir.resolve("sync"));
        Files.writeString(dir.resolve("sync/discord.yml"), "enabled: true\n");
        Files.writeString(dir.resolve("sync/websocket.yml"), "enabled: true\n");
        Files.writeString(dir.resolve("sync/telegram.yml"), "enabled: true\n");
        Files.writeString(dir.resolve("sync/http.yml"), "enabled: true\n");
        Files.writeString(dir.resolve("sync/velocity.yml"), "enabled: true\n");
        Files.writeString(dir.resolve("sync/tcp.yml"), "enabled: true\n");
        Files.writeString(dir.resolve("sync/udp.yml"), "enabled: true\n");
        Files.writeString(dir.resolve("sync/bus.yml"), "enabled: true\n");

        assertEquals(dir.resolve("sync/discord.yml"), SyncConfigPaths.discord(dir));
        assertEquals(dir.resolve("sync/websocket.yml"), SyncConfigPaths.webSocket(dir));
        assertEquals(dir.resolve("sync/telegram.yml"), SyncConfigPaths.telegram(dir));
        assertEquals(dir.resolve("sync/http.yml"), SyncConfigPaths.http(dir));
        assertEquals(dir.resolve("sync/velocity.yml"), SyncConfigPaths.velocity(dir));
        assertEquals(dir.resolve("sync/tcp.yml"), SyncConfigPaths.tcp(dir));
        assertEquals(dir.resolve("sync/udp.yml"), SyncConfigPaths.udp(dir));
        assertEquals(dir.resolve("sync/bus.yml"), SyncConfigPaths.bus(dir));
    }

    @Test
    void missingFilesFallBackToCanonicalDestination() {
        assertEquals(SyncConfigPaths.canonicalDiscord(dir), SyncConfigPaths.discord(dir));
        assertEquals(SyncConfigPaths.canonicalWebSocket(dir), SyncConfigPaths.webSocket(dir));
        assertEquals(SyncConfigPaths.canonicalTelegram(dir), SyncConfigPaths.telegram(dir));
        assertEquals(SyncConfigPaths.canonicalHttp(dir), SyncConfigPaths.http(dir));
        assertEquals(SyncConfigPaths.canonicalVelocity(dir), SyncConfigPaths.velocity(dir));
        assertEquals(SyncConfigPaths.canonicalTcp(dir), SyncConfigPaths.tcp(dir));
        assertEquals(SyncConfigPaths.canonicalUdp(dir), SyncConfigPaths.udp(dir));
        assertEquals(SyncConfigPaths.canonicalBus(dir), SyncConfigPaths.bus(dir));
    }
}

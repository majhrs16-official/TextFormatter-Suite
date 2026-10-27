package me.majhrs16.suite.syncsocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SocketConfigTest {

    @TempDir
    Path dir;

    private Path canonical(String name) throws Exception {
        Path file = dir.resolve("config/sync-socket/" + name + ".yml");
        Files.createDirectories(file.getParent());
        return file;
    }

    @Test
    void missingFilesYieldDisabledConfigs() {
        SocketConfig tcp = SocketConfig.loadTcp(dir);
        SocketConfig udp = SocketConfig.loadUdp(dir);

        assertFalse(tcp.enabled());
        assertFalse(udp.enabled());
        assertFalse(tcp.usable());
        assertFalse(udp.usable());
        assertEquals("", tcp.remoteHost());
        assertEquals(0, tcp.localPort());
    }

    @Test
    void readsTcpAndUdpSeparately() throws Exception {
        Files.writeString(canonical("tcp"), """
            enabled: true
            remoteHost: 10.0.0.1
            remotePort: 9090
            localPort: 9091
            """);
        Files.writeString(canonical("udp"), """
            enabled: true
            remoteHost: 10.0.0.2
            remotePort: 9092
            """);

        SocketConfig tcp = SocketConfig.loadTcp(dir);
        SocketConfig udp = SocketConfig.loadUdp(dir);

        assertTrue(tcp.usable());
        assertEquals("10.0.0.1", tcp.remoteHost());
        assertEquals(9090, tcp.remotePort());
        assertEquals(9091, tcp.localPort());
        assertTrue(udp.usable());
        assertEquals(9092, udp.remotePort());
        assertEquals(0, udp.localPort());
    }

    @Test
    void enabledWithoutRemoteIsNotUsable() throws Exception {
        Files.writeString(canonical("tcp"), "enabled: true\n");

        assertFalse(SocketConfig.loadTcp(dir).usable());
    }

    @Test
    void invalidPortsAreNormalised() throws Exception {
        Files.writeString(canonical("tcp"), """
            enabled: true
            remoteHost: host
            remotePort: 99999
            localPort: -1
            """);

        SocketConfig config = SocketConfig.loadTcp(dir);

        assertFalse(config.usable());
        assertEquals(0, config.remotePort());
        assertEquals(0, config.localPort());
    }

    @Test
    void legacyFilesAreReadDuringMigration() throws Exception {
        Path legacy = dir.resolve("sync/tcp.yml");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "enabled: true\nremoteHost: legacy\nremotePort: 9090\n");

        SocketConfig config = SocketConfig.loadTcp(dir);

        assertTrue(config.usable());
        assertEquals("legacy", config.remoteHost());
    }
}

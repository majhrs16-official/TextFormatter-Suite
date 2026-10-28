package me.majhrs16.suite.syncsocket;

import me.majhrs16.suite.synccommon.SyncConfigPaths;
import me.majhrs16.suite.synccommon.SyncYaml;

import java.nio.file.Path;
import java.util.Map;

/**
 * Configuración de los sinks TCP y UDP.
 *
 * <p>Ambos comparten esquema y defaults; solo cambia el archivo del que se
 * leen ({@code config/sync-socket/tcp.yml} y {@code .../udp.yml}, con fallback
 * a {@code sync/tcp.yml} y {@code sync/udp.yml}).</p>
 *
 * @param enabled    si el sink debe arrancar
 * @param remoteHost host remoto
 * @param remotePort puerto remoto (1-65535)
 * @param localPort  puerto local de escucha; {@code 0} desactiva el servidor local
 */
public record SocketConfig(boolean enabled, String remoteHost, int remotePort, int localPort) {

    public SocketConfig {
        remoteHost = remoteHost == null ? "" : remoteHost.trim();
        if (remotePort < 0 || remotePort > 65535) {
            remotePort = 0;
        }
        if (localPort < 0 || localPort > 65535) {
            localPort = 0;
        }
    }

    /** @param dataFolder carpeta de datos de la suite @return configuración TCP. */
    public static SocketConfig loadTcp(Path dataFolder) {
        return load(SyncConfigPaths.tcp(dataFolder));
    }

    /** @param dataFolder carpeta de datos de la suite @return configuración UDP. */
    public static SocketConfig loadUdp(Path dataFolder) {
        return load(SyncConfigPaths.udp(dataFolder));
    }

    private static SocketConfig load(Path file) {
        Map<String, Object> map = SyncYaml.readMap(file);
        return new SocketConfig(
            SyncYaml.bool(map, "enabled", false),
            SyncYaml.string(map, "remoteHost", ""),
            SyncYaml.integer(map, "remotePort", 0),
            SyncYaml.integer(map, "localPort", 0)
        );
    }

    /** @return {@code true} si hay destino remoto válido y el sink está habilitado. */
    public boolean usable() {
        return enabled && !remoteHost.isBlank() && remotePort > 0;
    }
}

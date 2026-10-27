package me.majhrs16.suite.syncwebsocket;

import me.majhrs16.suite.syncbus.SyncConfigPaths;
import me.majhrs16.suite.syncbus.SyncYaml;

import java.nio.file.Path;
import java.util.Map;

/**
 * Configuración propia del submódulo {@code sync-websocket}.
 *
 * <p>El módulo es dueño de su archivo ({@code config/sync-websocket/config.yml},
 * con fallback a {@code sync/websocket.yml}) y de las reglas de seguridad
 * asociadas: un servidor WebSocket habilitado sin token no es utilizable.</p>
 *
 * @param enabled si el sink debe arrancar
 * @param token   token de autenticación compartido con los clientes
 * @param port    puerto de escucha (1-65535)
 * @param bind    dirección de escucha
 */
public record WebSocketSyncConfig(boolean enabled, String token, int port, String bind) {

    public static final int DEFAULT_PORT = 9092;
    public static final String DEFAULT_BIND = "127.0.0.1";

    public WebSocketSyncConfig {
        token = token == null ? "" : token.trim();
        bind = bind == null || bind.isBlank() ? DEFAULT_BIND : bind.trim();
        if (port < 1 || port > 65535) {
            port = DEFAULT_PORT;
        }
    }

    /**
     * Carga la configuración del módulo.
     *
     * @param dataFolder carpeta de datos de la suite
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static WebSocketSyncConfig load(Path dataFolder) {
        Map<String, Object> map = SyncYaml.readMap(SyncConfigPaths.webSocket(dataFolder));
        return new WebSocketSyncConfig(
            SyncYaml.bool(map, "enabled", false),
            SyncYaml.string(map, "token", ""),
            SyncYaml.integer(map, "port", DEFAULT_PORT),
            SyncYaml.string(map, "bind", DEFAULT_BIND)
        );
    }

    /** @return configuración deshabilitada con valores por defecto. */
    public static WebSocketSyncConfig disabled() {
        return new WebSocketSyncConfig(false, "", DEFAULT_PORT, DEFAULT_BIND);
    }

    /** @return {@code true} si el sink puede arrancar con esta configuración. */
    public boolean usable() {
        return enabled && !token.isBlank();
    }
}

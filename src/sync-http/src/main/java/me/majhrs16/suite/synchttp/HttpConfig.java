package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.synccommon.SyncConfigPaths;
import me.majhrs16.suite.synccommon.SyncYaml;

import java.nio.file.Path;
import java.util.Map;

/**
 * Configuración propia del submódulo {@code sync-http}.
 *
 * <p>El sink necesita una URL de webhook para el tráfico saliente. El puerto de
 * entrada {@code 0} deja que el sistema elija uno efímero; el sink siempre
 * expone el endpoint local para recibir mensajes.</p>
 *
 * @param enabled      si el sink debe registrarse
 * @param outboundUrl  webhook de destino
 * @param inboundPort  puerto local de entrada ({@code 0} = efímero)
 * @param inboundPath  ruta del endpoint local
 * @param bind         dirección de escucha
 * @param authToken    token Bearer opcional
 * @param hmacSecret   secreto HMAC opcional
 */
public record HttpConfig(
        boolean enabled,
        String outboundUrl,
        int inboundPort,
        String inboundPath,
        String bind,
        String authToken,
        String hmacSecret) {

    public static final String DEFAULT_PATH = "/hook";
    public static final String DEFAULT_BIND = "127.0.0.1";

    public HttpConfig {
        outboundUrl = outboundUrl == null ? "" : outboundUrl.trim();
        inboundPath = inboundPath == null || inboundPath.isBlank() ? DEFAULT_PATH : inboundPath.trim();
        bind = bind == null || bind.isBlank() ? DEFAULT_BIND : bind.trim();
        authToken = authToken == null ? "" : authToken.trim();
        hmacSecret = hmacSecret == null ? "" : hmacSecret.trim();
    }

    /**
     * @param dataFolder carpeta de datos de la suite
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static HttpConfig load(Path dataFolder) {
        return loadFile(SyncConfigPaths.http(dataFolder));
    }

    /**
     * @param configDir carpeta privada del módulo ({@code config/sync-http})
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static HttpConfig loadFromConfigDir(Path configDir) {
        return loadFile(configDir.resolve("config.yml"));
    }

    private static HttpConfig loadFile(Path file) {
        Map<String, Object> map = SyncYaml.readMap(file);
        return new HttpConfig(
            SyncYaml.bool(map, "enabled", false),
            SyncYaml.string(map, "outboundUrl", ""),
            SyncYaml.integer(map, "inboundPort", 0),
            SyncYaml.string(map, "inboundPath", DEFAULT_PATH),
            SyncYaml.string(map, "bind", DEFAULT_BIND),
            SyncYaml.string(map, "authToken", ""),
            SyncYaml.string(map, "hmacSecret", "")
        );
    }

    /** @return {@code true} si hay una URL saliente y el puerto es válido. */
    public boolean usable() {
        return enabled && !outboundUrl.isBlank() && inboundPort >= 0 && inboundPort <= 65535;
    }
}

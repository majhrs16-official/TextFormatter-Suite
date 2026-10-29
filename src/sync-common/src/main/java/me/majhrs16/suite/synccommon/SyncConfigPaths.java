package me.majhrs16.suite.synccommon;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resuelve las rutas de configuración de sync dentro del layout de
 * {@code docs/prompts/diseno/storage.md}.
 * <p>
 * Cada submódulo ({@code sync-discord}, {@code sync-websocket}, ...) es dueño de
 * su carpeta dentro de {@code config/}. Las rutas planas heredadas
 * ({@code sync/}) se mantienen como fallback para no romper instalaciones
 * existentes.
 * </p>
 * <p>
 * Regla de resolución: canónica si existe → heredada si existe → canónica
 * (destino de escritura del primer arranque).
 * </p>
 */
public final class SyncConfigPaths {

    public static final String CONFIG_DIR = "config";

    private SyncConfigPaths() {
    }

    // -- rutas heredadas (solo lectura durante la migración) -----------------

    private static Path legacyDir(Path dataFolder) {
        return dataFolder.resolve("sync");
    }

    // -- rutas canónicas (destino de escritura) ------------------------------

    public static Path canonicalBus(Path dataFolder) {
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-bus").resolve("config.yml");
    }

    public static Path canonicalDiscord(Path dataFolder) {
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-discord").resolve("config.yml");
    }

    public static Path canonicalWebSocket(Path dataFolder) {
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-websocket").resolve("config.yml");
    }

    public static Path canonicalTelegram(Path dataFolder) {
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-telegram").resolve("config.yml");
    }

    public static Path canonicalHttp(Path dataFolder) {
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-http").resolve("config.yml");
    }

    public static Path canonicalVelocity(Path dataFolder) {
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-velocity").resolve("config.yml");
    }

    public static Path canonicalTcp(Path dataFolder) {
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-socket").resolve("tcp.yml");
    }

    public static Path canonicalUdp(Path dataFolder) {
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-socket").resolve("udp.yml");
    }

    // -- rutas resueltas (canónica si existe, si no la heredada) -------------

    public static Path bus(Path dataFolder) {
        return resolve(canonicalBus(dataFolder), legacyDir(dataFolder).resolve("bus.yml"));
    }

    public static Path discord(Path dataFolder) {
        return resolve(canonicalDiscord(dataFolder), legacyDir(dataFolder).resolve("discord.yml"));
    }

    public static Path webSocket(Path dataFolder) {
        return resolve(canonicalWebSocket(dataFolder), legacyDir(dataFolder).resolve("websocket.yml"));
    }

    public static Path telegram(Path dataFolder) {
        return resolve(canonicalTelegram(dataFolder), legacyDir(dataFolder).resolve("telegram.yml"));
    }

    public static Path http(Path dataFolder) {
        return resolve(canonicalHttp(dataFolder), legacyDir(dataFolder).resolve("http.yml"));
    }

    public static Path velocity(Path dataFolder) {
        return resolve(canonicalVelocity(dataFolder), legacyDir(dataFolder).resolve("velocity.yml"));
    }

    public static Path tcp(Path dataFolder) {
        return resolve(canonicalTcp(dataFolder), legacyDir(dataFolder).resolve("tcp.yml"));
    }

    public static Path udp(Path dataFolder) {
        return resolve(canonicalUdp(dataFolder), legacyDir(dataFolder).resolve("udp.yml"));
    }

    private static Path resolve(Path canonical, Path legacy) {
        if (Files.exists(canonical)) {
            return canonical;
        }
        if (Files.exists(legacy)) {
            return legacy;
        }
        return canonical;
    }
}

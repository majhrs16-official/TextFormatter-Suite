package me.majhrs16.suite.synccommon;

import java.nio.file.Path;

/**
 * Resuelve las rutas de configuración de sync dentro del layout de
 * {@code docs/prompts/diseno/storage.md}.
 * <p>
 * Cada submódulo ({@code sync-discord}, {@code sync-websocket}, ...) es dueño de
 * su carpeta dentro de {@code config/}. Las rutas planas {@code sync/} no se
 * leen ni se migran.
 * </p>
 */
public final class SyncConfigPaths {

    public static final String CONFIG_DIR = "config";

    private SyncConfigPaths() {
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
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-velocity-core").resolve("config.yml");
    }

    public static Path canonicalTcp(Path dataFolder) {
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-socket").resolve("tcp.yml");
    }

    public static Path canonicalUdp(Path dataFolder) {
        return dataFolder.resolve(CONFIG_DIR).resolve("sync-socket").resolve("udp.yml");
    }

    // -- rutas resueltas -----------------------------------------------------

    public static Path bus(Path dataFolder) {
        return canonicalBus(dataFolder);
    }

    public static Path discord(Path dataFolder) {
        return canonicalDiscord(dataFolder);
    }

    public static Path webSocket(Path dataFolder) {
        return canonicalWebSocket(dataFolder);
    }

    public static Path telegram(Path dataFolder) {
        return canonicalTelegram(dataFolder);
    }

    public static Path http(Path dataFolder) {
        return canonicalHttp(dataFolder);
    }

    public static Path velocity(Path dataFolder) {
        return canonicalVelocity(dataFolder);
    }

    public static Path tcp(Path dataFolder) {
        return canonicalTcp(dataFolder);
    }

    public static Path udp(Path dataFolder) {
        return canonicalUdp(dataFolder);
    }
}

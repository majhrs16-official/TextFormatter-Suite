package me.majhrs16.suite.synctelegram;

import me.majhrs16.suite.synccommon.SyncConfigPaths;
import me.majhrs16.suite.synccommon.SyncYaml;

import java.nio.file.Path;
import java.util.Map;

/**
 * Configuración propia del submódulo {@code sync-telegram}.
 *
 * <p>Un sink habilitado necesita token y {@code chatId} distinto de cero; en
 * caso contrario el adapter no lo crea (degradación a no-op).</p>
 *
 * @param enabled si el sink debe arrancar
 * @param token   token del bot
 * @param chatId  chat de destino/origen
 */
public record TelegramConfig(boolean enabled, String token, long chatId) {

    public TelegramConfig {
        token = token == null ? "" : token.trim();
    }

    /**
     * @param dataFolder carpeta de datos de la suite
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static TelegramConfig load(Path dataFolder) {
        return loadFile(SyncConfigPaths.telegram(dataFolder));
    }

    /**
     * @param configDir carpeta privada del módulo ({@code config/sync-telegram})
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static TelegramConfig loadFromConfigDir(Path configDir) {
        return loadFile(configDir.resolve("config.yml"));
    }

    private static TelegramConfig loadFile(Path file) {
        Map<String, Object> map = SyncYaml.readMap(file);
        return new TelegramConfig(
            SyncYaml.bool(map, "enabled", false),
            SyncYaml.string(map, "token", ""),
            SyncYaml.longValue(map, "chatId", 0L)
        );
    }

    /** @return {@code true} si hay token y chat configurados. */
    public boolean usable() {
        return enabled && !token.isBlank() && chatId != 0;
    }
}

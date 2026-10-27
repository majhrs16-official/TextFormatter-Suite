package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.syncbus.SyncConfigPaths;
import me.majhrs16.suite.syncbus.SyncYaml;

import java.nio.file.Path;
import java.util.Map;

/**
 * Configuración propia del submódulo {@code sync-discord}.
 *
 * <p>El token es obligatorio para conectar. El canal puede ser {@code 0}
 * mientras se termina de configurar: hay plataformas que conectan igualmente
 * (Spigot) y otras que necesitan el canal para construir el listener (Fabric).
 * La decisión queda en el adapter, que consulta {@link #hasChannel()}.</p>
 *
 * @param enabled   si el sink debe arrancar
 * @param token     token del bot
 * @param channelId canal de destino; {@code 0} = sin canal
 * @param format    plantilla de los mensajes que llegan desde Discord
 */
public record DiscordConfig(boolean enabled, String token, long channelId, String format) {

    public static final String DEFAULT_FORMAT = "{sender}: {content}";

    public DiscordConfig {
        token = token == null ? "" : token.trim();
        if (channelId < 0) {
            channelId = 0;
        }
        format = format == null || format.isBlank() ? DEFAULT_FORMAT : format;
    }

    /**
     * @param dataFolder carpeta de datos de la suite
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static DiscordConfig load(Path dataFolder) {
        Map<String, Object> map = SyncYaml.readMap(SyncConfigPaths.discord(dataFolder));
        return new DiscordConfig(
            SyncYaml.bool(map, "enabled", false),
            SyncYaml.string(map, "token", ""),
            SyncYaml.longValue(map, "channel", 0L),
            SyncYaml.string(map, "format", DEFAULT_FORMAT)
        );
    }

    /** @return {@code true} si el sink puede conectar. */
    public boolean usable() {
        return enabled && !token.isBlank();
    }

    /** @return {@code true} si hay un canal configurado para enviar. */
    public boolean hasChannel() {
        return channelId > 0;
    }
}

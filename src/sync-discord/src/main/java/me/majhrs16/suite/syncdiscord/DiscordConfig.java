package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.synccommon.SyncConfigPaths;
import me.majhrs16.suite.synccommon.SyncYaml;

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
 * @param transport adaptador de conexión ({@code jda}, {@code jdk})
 */
public record DiscordConfig(boolean enabled, String token, long channelId, String format,
                            String transport) {

    public static final String DEFAULT_FORMAT = "{sender}: {content}";
    public static final String DEFAULT_TRANSPORT = "jda";

    public DiscordConfig {
        token = token == null ? "" : token.trim();
        if (channelId < 0) {
            channelId = 0;
        }
        format = format == null || format.isBlank() ? DEFAULT_FORMAT : format;
        transport = transport == null || transport.isBlank() ? DEFAULT_TRANSPORT : transport.trim();
    }

    /**
     * @param dataFolder carpeta de datos de la suite
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static DiscordConfig load(Path dataFolder) {
        return loadFile(SyncConfigPaths.discord(dataFolder));
    }

    /**
     * @param configDir carpeta privada del módulo ({@code config/sync-discord})
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static DiscordConfig loadFromConfigDir(Path configDir) {
        return loadFile(configDir.resolve("config.yml"));
    }

    private static DiscordConfig loadFile(Path file) {
        Map<String, Object> map = SyncYaml.readMap(file);
        return new DiscordConfig(
            SyncYaml.bool(map, "enabled", false),
            SyncYaml.string(map, "token", ""),
            SyncYaml.longValue(map, "channel", 0L),
            SyncYaml.string(map, "format", DEFAULT_FORMAT),
            SyncYaml.string(map, "transport", DEFAULT_TRANSPORT)
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

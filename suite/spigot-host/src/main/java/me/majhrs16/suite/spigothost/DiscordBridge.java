package me.majhrs16.suite.spigothost;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.syncdiscord.JdaDiscordSink;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runtime wiring for the Discord border (A9): reads {@code sync/discord.yml}
 * ({@code enabled/token/channel}), owns the {@link JdaDiscordSink} lifecycle
 * and connects both directions:
 *
 * <ul>
 *   <li>outbound — the plugin mirrors chat broadcasts and typed events via
 *       {@link #mirror(Message)};</li>
 *   <li>inbound — MESSAGE_CREATE becomes a CHAT message on the conventional
 *       {@code discord} channel and runs through the normal dispatcher.</li>
 * </ul>
 *
 * <p>Disabled/incomplete config or failed start degrade to no-op (logged),
 * never fatal for the rest of the suite.</p>
 */
public final class DiscordBridge {

    private final JdaDiscordSink sink;
    private final MessageDispatcher dispatcher;
    private final PluginLogger logger;

    private DiscordBridge(JdaDiscordSink sink, MessageDispatcher dispatcher, PluginLogger logger) {
        this.sink = sink;
        this.dispatcher = dispatcher;
        this.logger = logger;
    }

    /** @return an active bridge, or {@code null} when disabled/unavailable. */
    public static DiscordBridge create(Path dataFolder, MessageDispatcher dispatcher,
                                       PluginLogger logger) {
        Path file = dataFolder.resolve("sync").resolve("discord.yml");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Object root = new Yaml().load(Files.readString(file));
            if (!(root instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;
            boolean enabled = Boolean.TRUE.equals(map.get("enabled"));
            String token = map.get("token") == null ? "" : String.valueOf(map.get("token")).trim();
            long channelId = parseChannel(map.get("channel"));
            if (!enabled || token.isEmpty() || channelId <= 0) {
                return null;
            }
            return new DiscordBridge(new JdaDiscordSink(token, channelId), dispatcher, logger);
        } catch (IOException | RuntimeException exception) {
            logger.warn("discord.yml ilegible: " + exception.getMessage());
            return null;
        }
    }

    /** Connects to Discord; failure logs and leaves the bridge inactive. */
    public void start() {
        try {
            sink.setListener(new me.majhrs16.suite.api.spi.SyncListener() {
                @Override
                public void onMessage(me.majhrs16.suite.api.spi.SyncSink source,
                                      Message incoming) {
                    // Reencaminado por el pipeline completo: iFlow decide,
                    // TextFormatter renderiza/traduce y ChatDelivery entrega.
                    dispatcher.dispatch(incoming);
                }

                @Override
                public void onDisconnect(me.majhrs16.suite.api.spi.SyncSink source,
                                         String reason) {
                    logger.warn("Discord desconectado: " + reason);
                }
            });
            sink.start();
            logger.info("Discord conectado");
        } catch (Exception exception) {
            logger.warn("Discord no conectado: " + exception.getMessage());
        }
    }

    public void stop() {
        sink.stop();
    }

    /** Best-effort outbound copy; never interrupts game delivery. */
    public void mirror(Message outgoing) {
        try {
            sink.send(outgoing);
        } catch (Exception exception) {
            logger.debug("espejo Discord falló: " + exception.getMessage());
        }
    }

    private static long parseChannel(Object raw) {
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}

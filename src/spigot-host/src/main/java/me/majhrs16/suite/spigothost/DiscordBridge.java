package me.majhrs16.suite.spigothost;

import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.DispatchReport;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.syncdiscord.DiscordConfig;
import me.majhrs16.suite.syncdiscord.DiscordSink;
import me.majhrs16.suite.syncdiscord.jda.JdaDiscordConnectionFactory;
import me.majhrs16.suite.api.spi.SyncBus;

import java.nio.file.Path;

/**
 * Runtime wiring for the Discord border (A9): reads {@code sync/discord.yml}
 * ({@code enabled/token/channel}), owns the {@link DiscordSink} lifecycle
 * and connects both directions:
 *
 * <ul>
 *   <li>outbound — the plugin mirrors chat broadcasts and typed events via
 *       {@link SyncBus#broadcast(Message)};</li>
 *   <li>inbound — MESSAGE_CREATE becomes a CHAT message on the conventional
 *       {@code discord} channel and runs through the normal dispatcher.</li>
 * </ul>
 *
 * <p>Disabled/incomplete config or failed start degrade to no-op (logged),
 * never fatal for the rest of the suite.</p>
 */
public final class DiscordBridge {

    private final DiscordSink sink;
    private final MessageDispatcher dispatcher;
    private final PluginLogger logger;
    private final SyncBus syncBus;

    private DiscordBridge(DiscordSink sink, MessageDispatcher dispatcher, PluginLogger logger, SyncBus syncBus) {
        this.sink = sink;
        this.dispatcher = dispatcher;
        this.logger = logger;
        this.syncBus = syncBus;
    }

    /** @return the underlying Discord sink for registration with SyncBus. */
    public DiscordSink getSink() {
        return sink;
    }

    /** @return an active bridge, or {@code null} when disabled/unavailable. */
    public static DiscordBridge create(Path dataFolder, MessageDispatcher dispatcher,
                                       PluginLogger logger, SyncBus syncBus) {
        DiscordConfig config = DiscordConfig.load(dataFolder);
        if (!config.usable()) {
            return null;
        }
        // If channel is 0 or invalid, still create the bridge but it won't send messages
        // until a valid channel is configured
        if (!config.hasChannel()) {
            logger.warn("Discord channel ID not configured (channel=0); bridge created but will not send messages");
        }
        return new DiscordBridge(
            new DiscordSink(
                new JdaDiscordConnectionFactory(),
                new DiscordSettings(config.token(), config.channelId()),
                logger),
            dispatcher, logger, syncBus);
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
            logger.info("Discord iniciado (conexión asíncrona)");
        } catch (Exception exception) {
            logger.warn("Discord no conectado: " + exception.getMessage());
        }
    }

    public void stop() {
        sink.stop();
    }

    /** Best-effort outbound copy via SyncBus; only mirrors messages that were delivered. */
    public void mirror(Message outgoing, DispatchReport report) {
        // Only mirror if the message was actually delivered to someone
        if (report.delivered() > 0 || report.channelRedirected() > 0 || report.redirected() > 0) {
            try {
                if (syncBus != null) {
                    syncBus.broadcast(outgoing);
                } else {
                    // Fallback if SyncBus not available
                    sink.send(outgoing);
                }
            } catch (Exception exception) {
                logger.debug("espejo Discord falló: " + exception.getMessage());
            }
        }
    }
}

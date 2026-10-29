package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.discord.DiscordConnection;
import me.majhrs16.suite.api.discord.DiscordConnectionFactory;
import me.majhrs16.suite.api.discord.DiscordListener;
import me.majhrs16.suite.api.discord.DiscordMessage;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import java.util.Objects;

/**
 * Neutral Discord edge.
 *
 * <p>This class contains only the module logic: it maps the suite message
 * contract to a {@link DiscordConnection} port and translates inbound
 * {@link DiscordMessage} values back to the suite pipeline. The concrete
 * technology (JDA, JDK WebSocket, a fake in tests) is supplied by a
 * {@link DiscordConnectionFactory}; this package never imports it.</p>
 */
public final class DiscordSink implements SyncSink {

    private final DiscordConnectionFactory factory;
    private final DiscordSettings settings;
    private final PluginLogger logger;

    private volatile SyncListener listener;
    private volatile DiscordConnection connection;

    public DiscordSink(DiscordConnectionFactory factory, DiscordSettings settings,
                       PluginLogger logger) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String name() {
        return "discord";
    }

    @Override
    public DeliverySemantics deliverySemantics() {
        return DeliverySemantics.AT_LEAST_ONCE;
    }

    @Override
    public Ordering ordering() {
        return Ordering.PER_CHANNEL;
    }

    @Override
    public synchronized void start() throws Exception {
        if (connection != null) {
            return;
        }
        DiscordConnection created = factory.create(settings, logger);
        created.setListener(new Inbound());
        try {
            created.connect();
        } catch (Exception failure) {
            created.close();
            throw failure;
        }
        connection = created;
        logger.info("Discord connection started with adapter " + factory.name());
    }

    @Override
    public synchronized void stop() {
        DiscordConnection current = connection;
        connection = null;
        if (current != null) {
            current.close();
            logger.info("Discord connection stopped");
        }
    }

    @Override
    public void send(Message message) throws Exception {
        DiscordConnection current = connection;
        if (current == null || !current.isConnected()) {
            throw new IllegalStateException("Discord sink is not connected");
        }
        current.send(message.text());
    }

    @Override
    public void setListener(SyncListener syncListener) {
        this.listener = syncListener;
    }

    private final class Inbound implements DiscordListener {

        @Override
        public void onMessage(DiscordMessage message) {
            SyncListener current = listener;
            if (current == null) {
                return;
            }
            Message incoming = Message.builder()
                .sender(Actor.unknown(message.author()))
                .direction(Direction.others())
                .text(message.content())
                .channel("discord")
                .build();
            current.onMessage(DiscordSink.this, incoming);
        }

        @Override
        public void onDisconnect(String reason) {
            SyncListener current = listener;
            if (current != null) {
                current.onDisconnect(DiscordSink.this, reason);
            }
        }
    }
}

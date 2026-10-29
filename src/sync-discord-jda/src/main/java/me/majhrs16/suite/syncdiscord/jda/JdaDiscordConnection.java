package me.majhrs16.suite.syncdiscord.jda;

import me.majhrs16.suite.api.discord.DiscordConnection;
import me.majhrs16.suite.api.discord.DiscordListener;
import me.majhrs16.suite.api.discord.DiscordMessage;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.spi.PluginLogger;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;

/**
 * JDA technology adapter for the {@link DiscordConnection} port.
 *
 * <p>This is the only class in the Discord packages that imports JDA. It owns
 * the bot token, the JDA lifecycle and the translation from JDA events to
 * {@link DiscordMessage}. {@link #connect()} returns as soon as JDA has been
 * scheduled, so package activation never waits for the network.</p>
 */
public final class JdaDiscordConnection implements DiscordConnection {

    private final char[] token;
    private final long channelId;
    private final PluginLogger logger;

    private volatile JDA jda;
    private volatile DiscordListener listener;
    private volatile boolean closed;

    public JdaDiscordConnection(DiscordSettings settings, PluginLogger logger) {
        Objects.requireNonNull(settings, "settings");
        if (settings.token().isBlank()) {
            throw new IllegalArgumentException("Discord token must not be blank");
        }
        this.token = settings.token().toCharArray();
        this.channelId = settings.channelId();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public synchronized void connect() {
        if (closed || jda != null) {
            return;
        }
        jda = JDABuilder.createLight(new String(token), EnumSet.of(
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT))
            .setChunkingFilter(ChunkingFilter.NONE)
            .addEventListeners(new Inbound())
            .build();
        // JDA owns its own copy; the adapter copy is only needed until build().
        Arrays.fill(token, '\0');
    }

    @Override
    public boolean isConnected() {
        JDA current = jda;
        return !closed && current != null && current.getStatus() == JDA.Status.CONNECTED;
    }

    @Override
    public void send(String text) {
        JDA current = jda;
        if (closed || current == null || current.getStatus() != JDA.Status.CONNECTED) {
            throw new IllegalStateException("Discord connection is not ready");
        }
        TextChannel channel = current.getTextChannelById(channelId);
        if (channel == null) {
            throw new IllegalStateException(
                "Discord channel not found or not cached: " + channelId);
        }
        channel.sendMessage(text).complete();
    }

    @Override
    public void setListener(DiscordListener listener) {
        this.listener = listener;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        JDA current = jda;
        jda = null;
        if (current != null) {
            current.shutdownNow();
        }
        Arrays.fill(token, '\0');
    }

    private final class Inbound extends ListenerAdapter {

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (closed
                || event.getAuthor().isBot()
                || event.getChannel().getIdLong() != channelId) {
                return;
            }
            String content = event.getMessage().getContentDisplay();
            if (content == null || content.isBlank()) {
                return;
            }
            DiscordListener current = listener;
            if (current == null) {
                return;
            }
            current.onMessage(new DiscordMessage(
                event.getAuthor().getEffectiveName(), content, channelId));
        }
    }
}

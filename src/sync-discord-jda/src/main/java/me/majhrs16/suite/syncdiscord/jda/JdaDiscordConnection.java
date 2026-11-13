package me.majhrs16.suite.syncdiscord.jda;

import me.majhrs16.suite.api.discord.DiscordConnection;
import me.majhrs16.suite.api.discord.DiscordListener;
import me.majhrs16.suite.api.discord.DiscordMessage;
import me.majhrs16.suite.api.discord.DiscordOperation;
import me.majhrs16.suite.api.discord.DiscordResult;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.spi.PluginLogger;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.utils.ChunkingFilter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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
    public DiscordResult execute(DiscordOperation operation) {
        Objects.requireNonNull(operation, "operation");
        JDA current = jda;
        if (closed || current == null || current.getStatus() != JDA.Status.CONNECTED) {
            return new DiscordResult.Failed("Discord connection is not ready");
        }
        try {
            if (operation instanceof DiscordOperation.CreateChannel create) {
                return createChannel(current, create);
            }
            if (operation instanceof DiscordOperation.DeleteChannel delete) {
                return deleteChannel(current, delete);
            }
            if (operation instanceof DiscordOperation.DeleteMessage delete) {
                return deleteMessage(current, delete);
            }
            if (operation instanceof DiscordOperation.BanMember ban) {
                return banMember(current, ban);
            }
            if (operation instanceof DiscordOperation.AssignRole assign) {
                return assignRole(current, assign);
            }
            return new DiscordResult.Failed(
                "unsupported Discord operation: " + operation.getClass().getSimpleName());
        } catch (ErrorResponseException failure) {
            return map(failure);
        }
    }

    private static DiscordResult createChannel(JDA jda,
                                               DiscordOperation.CreateChannel operation) {
        Guild guild = jda.getGuildById(operation.guildId());
        if (guild == null) {
            return new DiscordResult.NotFound(
                "guild " + operation.guildId() + " is not available");
        }
        ChannelAction<TextChannel> action = guild.createTextChannel(operation.name());
        if (operation.topic() != null) {
            action = action.setTopic(operation.topic());
        }
        TextChannel channel = action.complete();
        return new DiscordResult.Ok(Map.of(
            "id", channel.getId(),
            "name", channel.getName()));
    }

    private static DiscordResult deleteChannel(JDA jda,
                                               DiscordOperation.DeleteChannel operation) {
        TextChannel channel = jda.getTextChannelById(operation.channelId());
        if (channel == null) {
            return new DiscordResult.NotFound(
                "channel " + operation.channelId() + " is not available");
        }
        AuditableRestAction<Void> action = channel.delete();
        if (operation.reason() != null) {
            action = action.reason(operation.reason());
        }
        action.complete();
        return new DiscordResult.Ok(Map.of());
    }

    private static DiscordResult deleteMessage(JDA jda,
                                               DiscordOperation.DeleteMessage operation) {
        TextChannel channel = jda.getTextChannelById(operation.channelId());
        if (channel == null) {
            return new DiscordResult.NotFound(
                "channel " + operation.channelId() + " is not available");
        }
        AuditableRestAction<Void> action =
            channel.deleteMessageById(operation.messageId());
        if (operation.reason() != null) {
            action = action.reason(operation.reason());
        }
        action.complete();
        return new DiscordResult.Ok(Map.of());
    }

    private static DiscordResult banMember(JDA jda,
                                           DiscordOperation.BanMember operation) {
        Guild guild = jda.getGuildById(operation.guildId());
        if (guild == null) {
            return new DiscordResult.NotFound(
                "guild " + operation.guildId() + " is not available");
        }
        AuditableRestAction<Void> action = guild.ban(
            UserSnowflake.fromId(operation.userId()),
            operation.deleteMessageSeconds(),
            TimeUnit.SECONDS);
        if (operation.reason() != null) {
            action = action.reason(operation.reason());
        }
        action.complete();
        return new DiscordResult.Ok(Map.of());
    }

    private static DiscordResult assignRole(JDA jda,
                                            DiscordOperation.AssignRole operation) {
        Guild guild = jda.getGuildById(operation.guildId());
        if (guild == null) {
            return new DiscordResult.NotFound(
                "guild " + operation.guildId() + " is not available");
        }
        Role role = guild.getRoleById(operation.roleId());
        if (role == null) {
            return new DiscordResult.NotFound(
                "role " + operation.roleId() + " is not available");
        }
        AuditableRestAction<Void> action = guild.addRoleToMember(
            UserSnowflake.fromId(operation.userId()), role);
        if (operation.reason() != null) {
            action = action.reason(operation.reason());
        }
        action.complete();
        return new DiscordResult.Ok(Map.of());
    }

    private static DiscordResult map(ErrorResponseException failure) {
        ErrorResponse response = failure.getErrorResponse();
        if (response == ErrorResponse.UNKNOWN_CHANNEL
            || response == ErrorResponse.UNKNOWN_MESSAGE
            || response == ErrorResponse.UNKNOWN_GUILD
            || response == ErrorResponse.UNKNOWN_MEMBER
            || response == ErrorResponse.UNKNOWN_USER
            || response == ErrorResponse.UNKNOWN_ROLE) {
            return new DiscordResult.NotFound(failure.getMeaning());
        }
        if (response == ErrorResponse.MISSING_ACCESS
            || response == ErrorResponse.MISSING_PERMISSIONS
            || response == ErrorResponse.NOT_AUTHORIZED_PERFORM_ACTION) {
            return new DiscordResult.Forbidden(failure.getMeaning());
        }
        return new DiscordResult.Failed(failure.getMeaning());
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

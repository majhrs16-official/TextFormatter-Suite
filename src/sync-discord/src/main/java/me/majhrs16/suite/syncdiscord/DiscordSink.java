package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.discord.DiscordConnection;
import me.majhrs16.suite.api.discord.DiscordConnectionFactory;
import me.majhrs16.suite.api.discord.DiscordListener;
import me.majhrs16.suite.api.discord.DiscordMessage;
import me.majhrs16.suite.api.discord.DiscordOperation;
import me.majhrs16.suite.api.discord.DiscordResult;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncActionSpec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncParamSpec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.api.sync.SyncValueType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private static final String CHANNEL_CREATE = "channel.create";
    private static final String CHANNEL_DELETE = "channel.delete";
    private static final String MESSAGE_DELETE = "message.delete";
    private static final String MESSAGE_EDIT = "message.edit";
    private static final String MEMBER_BAN = "member.ban";
    private static final String ROLE_ASSIGN = "role.assign";

    private static final Set<String> NATIVE_ACTIONS = Set.of(
            CHANNEL_CREATE, CHANNEL_DELETE, MESSAGE_DELETE, MESSAGE_EDIT,
            MEMBER_BAN, ROLE_ASSIGN);

    private static final SyncSurface SURFACE = buildSurface();

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
    public SyncSurface surface() {
        return SURFACE;
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
    public SyncResult invoke(SyncCall call) {
        Objects.requireNonNull(call, "call");
        if (!name().equals(call.backend()) || !NATIVE_ACTIONS.contains(call.action())) {
            return SyncSink.super.invoke(call);
        }
        if (call.principal() instanceof Principal.Official) {
            return new SyncResult.Denied(
                "Principal.Official may only invoke the least common multiple");
        }

        DiscordOperation operation;
        try {
            operation = operationOf(call);
        } catch (IllegalArgumentException invalid) {
            return new SyncResult.InvalidArgument(invalid.getMessage());
        }

        DiscordConnection current = connection;
        if (current == null || !current.isConnected()) {
            return new SyncResult.HostFailure("Discord sink is not connected");
        }
        try {
            return map(current.execute(operation));
        } catch (Exception failure) {
            return new SyncResult.HostFailure(describe(failure));
        }
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

    private static SyncSurface buildSurface() {
        SyncSurface lcm = SyncSurface.lcm("discord", SyncSurface.CURRENT_CONTRACT_VERSION);
        Set<SyncActionSpec> actions = new LinkedHashSet<>(lcm.actions());
        actions.add(new SyncActionSpec(
            CHANNEL_CREATE,
            ActionKind.WRITE,
            List.of(
                SyncParamSpec.required("guild", SyncValueType.STRING),
                SyncParamSpec.required("name", SyncValueType.STRING),
                SyncParamSpec.optional("topic", SyncValueType.STRING)),
            SyncParamSpec.optional("channel", SyncValueType.OBJECT),
            false,
            15_000));
        actions.add(new SyncActionSpec(
            CHANNEL_DELETE,
            ActionKind.DESTRUCTIVE,
            List.of(
                SyncParamSpec.required("channel", SyncValueType.CHANNEL_REF),
                SyncParamSpec.optional("reason", SyncValueType.STRING)),
            null,
            false,
            15_000));
        actions.add(new SyncActionSpec(
            MESSAGE_DELETE,
            ActionKind.DESTRUCTIVE,
            List.of(
                SyncParamSpec.required("channel", SyncValueType.CHANNEL_REF),
                SyncParamSpec.required("message", SyncValueType.STRING),
                SyncParamSpec.optional("reason", SyncValueType.STRING)),
            null,
            false,
            15_000));
        actions.add(new SyncActionSpec(
            MESSAGE_EDIT,
            ActionKind.WRITE,
            List.of(
                SyncParamSpec.required("channel", SyncValueType.CHANNEL_REF),
                SyncParamSpec.required("message", SyncValueType.STRING),
                SyncParamSpec.required("content", SyncValueType.STRING)),
            SyncParamSpec.optional("message", SyncValueType.OBJECT),
            true,
            15_000));
        actions.add(new SyncActionSpec(
            MEMBER_BAN,
            ActionKind.MODERATION,
            List.of(
                SyncParamSpec.required("guild", SyncValueType.STRING),
                SyncParamSpec.required("user", SyncValueType.ACTOR_REF),
                SyncParamSpec.optional("reason", SyncValueType.STRING),
                SyncParamSpec.optional("deleteMessageSeconds", SyncValueType.INTEGER)),
            null,
            true,
            15_000));
        actions.add(new SyncActionSpec(
            ROLE_ASSIGN,
            ActionKind.MODERATION,
            List.of(
                SyncParamSpec.required("guild", SyncValueType.STRING),
                SyncParamSpec.required("user", SyncValueType.ACTOR_REF),
                SyncParamSpec.required("role", SyncValueType.ROLE_REF),
                SyncParamSpec.optional("reason", SyncValueType.STRING)),
            null,
            true,
            15_000));
        return new SyncSurface("discord", SyncSurface.CURRENT_CONTRACT_VERSION, actions);
    }

    private static DiscordOperation operationOf(SyncCall call) {
        return switch (call.action()) {
            case CHANNEL_CREATE -> new DiscordOperation.CreateChannel(
                text(call, "guild"),
                text(call, "name"),
                optionalText(call, "topic"));
            case CHANNEL_DELETE -> new DiscordOperation.DeleteChannel(
                text(call, "channel"),
                optionalText(call, "reason"));
            case MESSAGE_DELETE -> new DiscordOperation.DeleteMessage(
                text(call, "channel"),
                text(call, "message"),
                optionalText(call, "reason"));
            case MESSAGE_EDIT -> new DiscordOperation.EditMessage(
                text(call, "channel"),
                text(call, "message"),
                text(call, "content"));
            case MEMBER_BAN -> new DiscordOperation.BanMember(
                text(call, "guild"),
                text(call, "user"),
                integer(call, "deleteMessageSeconds"),
                optionalText(call, "reason"));
            case ROLE_ASSIGN -> new DiscordOperation.AssignRole(
                text(call, "guild"),
                text(call, "user"),
                text(call, "role"),
                optionalText(call, "reason"));
            default -> throw new IllegalArgumentException(
                "unsupported Discord action: " + call.action());
        };
    }

    private static String text(SyncCall call, String name) {
        Object value = call.args().get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                "'" + name + "' must be a non-blank string");
        }
        return text;
    }

    private static String optionalText(SyncCall call, String name) {
        Object value = call.args().get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("'" + name + "' must be a string");
        }
        return text.isBlank() ? null : text;
    }

    private static int integer(SyncCall call, String name) {
        Object value = call.args().get(name);
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)) {
            throw new IllegalArgumentException("'" + name + "' must be an integer");
        }
        return ((Number) value).intValue();
    }

    private static SyncResult map(DiscordResult result) {
        if (result instanceof DiscordResult.Ok ok) {
            return new SyncResult.Ok(ok.value());
        }
        if (result instanceof DiscordResult.NotFound notFound) {
            return new SyncResult.NotFound(notFound.reason());
        }
        if (result instanceof DiscordResult.Forbidden forbidden) {
            return new SyncResult.HostFailure(
                "Discord denied the action: " + forbidden.reason());
        }
        if (result instanceof DiscordResult.Conflict conflict) {
            return new SyncResult.Conflict(conflict.reason());
        }
        if (result instanceof DiscordResult.RateLimited limited) {
            return new SyncResult.RateLimited(limited.retryAfterMillis());
        }
        if (result instanceof DiscordResult.Failed failed) {
            return new SyncResult.HostFailure(failed.reason());
        }
        return new SyncResult.HostFailure("unknown Discord result");
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
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

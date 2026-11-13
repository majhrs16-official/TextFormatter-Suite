package me.majhrs16.suite.syncdiscord.jdk;

import me.majhrs16.suite.api.discord.DiscordConnection;
import me.majhrs16.suite.api.discord.DiscordListener;
import me.majhrs16.suite.api.discord.DiscordOperation;
import me.majhrs16.suite.api.discord.DiscordResult;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.transport.Transport;
import me.majhrs16.suite.transport.TransportException;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Zero-dependency Discord adapter: REST outbound through {@link Transport} and
 * inbound through the JDK WebSocket gateway.
 *
 * <p>{@link #connect()} returns immediately; the gateway handshake runs on a
 * daemon thread. Outbound sends only need the token, so they are available as
 * soon as the adapter is connected, even while the gateway is still
 * negotiating.</p>
 */
public final class JdkDiscordConnection implements DiscordConnection {

    public static final String API_BASE = "https://discord.com/api/v10";
    public static final String DEFAULT_GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json";
    public static final int INTENT_GUILD_MESSAGES = 1 << 9;
    public static final int INTENT_MESSAGE_CONTENT = 1 << 15;

    private final String apiBase;
    private final String gatewayUrl;
    private final char[] token;
    private final long channelId;
    private final int intents;
    private final Transport transport;
    private final PluginLogger logger;

    private volatile DiscordListener listener;
    private volatile JdkDiscordGateway gateway;
    private volatile Thread gatewayThread;
    private volatile boolean closed;

    public JdkDiscordConnection(DiscordSettings settings, PluginLogger logger,
                                Transport transport) {
        this(API_BASE, DEFAULT_GATEWAY, settings.token(), settings.channelId(),
                INTENT_GUILD_MESSAGES | INTENT_MESSAGE_CONTENT, transport, logger);
    }

    JdkDiscordConnection(String apiBase, String gatewayUrl, String token, long channelId,
                         int intents, Transport transport, PluginLogger logger) {
        this.apiBase = Objects.requireNonNull(apiBase, "apiBase");
        this.gatewayUrl = Objects.requireNonNull(gatewayUrl, "gatewayUrl");
        String tokenValue = Objects.requireNonNull(token, "token");
        if (tokenValue.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        this.token = tokenValue.toCharArray();
        this.channelId = channelId;
        this.intents = intents;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public synchronized void connect() {
        if (closed || gatewayThread != null) {
            return;
        }
        JdkDiscordGateway created = new JdkDiscordGateway(
                gatewayUrl, tokenString(), intents, new DiscordListener() {
                    @Override
                    public void onMessage(me.majhrs16.suite.api.discord.DiscordMessage message) {
                        DiscordListener current = listener;
                        if (current != null) {
                            current.onMessage(message);
                        }
                    }

                    @Override
                    public void onDisconnect(String reason) {
                        DiscordListener current = listener;
                        if (current != null) {
                            current.onDisconnect(reason);
                        }
                    }
                });
        gateway = created;
        Thread thread = new Thread(() -> {
            try {
                if (!created.connect() && !closed) {
                    logger.warn("Discord JDK gateway did not reach READY");
                }
            } catch (Exception failure) {
                if (!closed) {
                    logger.warn("Discord JDK gateway failed: " + failure.getMessage());
                }
            }
        }, "discord-jdk-gateway");
        thread.setDaemon(true);
        gatewayThread = thread;
        thread.start();
    }

    @Override
    public boolean isConnected() {
        return !closed && hasToken();
    }

    @Override
    public void send(String text) throws Exception {
        String response = transport.post(
            apiBase + "/channels/" + channelId + "/messages",
            Map.of("Authorization", "Bot " + tokenString(),
                "Content-Type", "application/json; charset=utf-8"),
            new JSONObject().put("content", text).toString());
        JSONObject created = new JSONObject(response);
        if (created.optInt("code", 0) != 0 || created.optString("id", "").isEmpty()) {
            throw new IllegalStateException("discord send failed: " + response);
        }
    }

    @Override
    public DiscordResult execute(DiscordOperation operation) throws Exception {
        Objects.requireNonNull(operation, "operation");
        if (closed || !hasToken()) {
            return new DiscordResult.Failed("Discord connection is not ready");
        }
        try {
            if (operation instanceof DiscordOperation.CreateChannel create) {
                return createChannel(create);
            }
            if (operation instanceof DiscordOperation.DeleteChannel delete) {
                return deleteChannel(delete);
            }
            if (operation instanceof DiscordOperation.DeleteMessage delete) {
                return deleteMessage(delete);
            }
            if (operation instanceof DiscordOperation.BanMember ban) {
                return banMember(ban);
            }
            if (operation instanceof DiscordOperation.AssignRole assign) {
                return assignRole(assign);
            }
            return new DiscordResult.Failed(
                "unsupported Discord operation: " + operation.getClass().getSimpleName());
        } catch (TransportException failure) {
            return map(failure);
        }
    }

    private DiscordResult createChannel(DiscordOperation.CreateChannel operation)
            throws Exception {
        JSONObject body = new JSONObject()
            .put("name", operation.name())
            .put("type", 0);
        if (operation.topic() != null) {
            body.put("topic", operation.topic());
        }
        String response = transport.post(
            apiBase + "/guilds/" + operation.guildId() + "/channels",
            headers(null),
            body.toString());
        JSONObject created = new JSONObject(response);
        String id = created.optString("id", "");
        if (id.isEmpty()) {
            return new DiscordResult.Failed(
                "discord did not return a channel id: " + response);
        }
        return new DiscordResult.Ok(Map.of(
            "id", id,
            "name", created.optString("name", operation.name())));
    }

    private DiscordResult deleteChannel(DiscordOperation.DeleteChannel operation)
            throws Exception {
        transport.delete(
            apiBase + "/channels/" + operation.channelId(),
            headers(operation.reason()));
        return new DiscordResult.Ok(Map.of());
    }

    private DiscordResult deleteMessage(DiscordOperation.DeleteMessage operation)
            throws Exception {
        transport.delete(
            apiBase + "/channels/" + operation.channelId()
                + "/messages/" + operation.messageId(),
            headers(operation.reason()));
        return new DiscordResult.Ok(Map.of());
    }

    private DiscordResult banMember(DiscordOperation.BanMember operation) throws Exception {
        JSONObject body = new JSONObject()
            .put("delete_message_seconds", operation.deleteMessageSeconds());
        transport.put(
            apiBase + "/guilds/" + operation.guildId() + "/bans/" + operation.userId(),
            headers(operation.reason()),
            body.toString());
        return new DiscordResult.Ok(Map.of());
    }

    private DiscordResult assignRole(DiscordOperation.AssignRole operation) throws Exception {
        transport.put(
            apiBase + "/guilds/" + operation.guildId()
                + "/members/" + operation.userId()
                + "/roles/" + operation.roleId(),
            headers(operation.reason()),
            null);
        return new DiscordResult.Ok(Map.of());
    }

    private Map<String, String> headers(String reason) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bot " + tokenString());
        headers.put("Content-Type", "application/json; charset=utf-8");
        if (reason != null && !reason.isBlank()) {
            headers.put("X-Audit-Log-Reason",
                URLEncoder.encode(reason, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return headers;
    }

    private static DiscordResult map(TransportException failure) {
        int status = failure.statusCode();
        if (status == 404) {
            return new DiscordResult.NotFound(failure.body());
        }
        if (status == 401 || status == 403) {
            return new DiscordResult.Forbidden(failure.body());
        }
        if (status == 409) {
            return new DiscordResult.Conflict(failure.body());
        }
        if (status == 429) {
            long retryAfter = retryAfterMillis(failure.body());
            return retryAfter < 0
                ? new DiscordResult.Failed(failure.getMessage())
                : new DiscordResult.RateLimited(retryAfter);
        }
        return new DiscordResult.Failed(failure.getMessage());
    }

    private static long retryAfterMillis(String body) {
        try {
            double seconds = new JSONObject(body).optDouble("retry_after", -1);
            return seconds < 0 ? -1 : Math.round(seconds * 1000);
        } catch (RuntimeException malformed) {
            return -1;
        }
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
        JdkDiscordGateway current = gateway;
        gateway = null;
        if (current != null) {
            current.close();
        }
        Thread thread = gatewayThread;
        gatewayThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        Arrays.fill(token, '\0');
    }

    private boolean hasToken() {
        for (char c : token) {
            if (c != '\0') {
                return true;
            }
        }
        return false;
    }

    private String tokenString() {
        if (!hasToken()) {
            throw new IllegalStateException(
                "Discord token is no longer available; the connection was closed");
        }
        return new String(token);
    }
}

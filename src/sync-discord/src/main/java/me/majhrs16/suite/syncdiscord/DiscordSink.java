package me.majhrs16.suite.syncdiscord;
import me.majhrs16.suite.transport.Transport;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Discord edge. Outbound: REST {@code POST /channels/{id}/messages} with a bot
 * token. Inbound: a {@link DiscordGateway} WebSocket session feeds messages to
 * the configured {@link SyncListener}.
 */
public final class DiscordSink implements SyncSink {

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
    private volatile SyncListener listener;
    private volatile DiscordGateway gateway;

    public DiscordSink(String token, long channelId, Transport transport) {
        this(API_BASE, DEFAULT_GATEWAY, token, channelId,
            INTENT_GUILD_MESSAGES | INTENT_MESSAGE_CONTENT, transport);
    }

    DiscordSink(String apiBase, String gatewayUrl, String token, long channelId,
                int intents, Transport transport) {
        this.apiBase = apiBase;
        this.gatewayUrl = gatewayUrl;
        String tokenValue = Objects.requireNonNull(token, "token");
        if (tokenValue.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        this.token = tokenValue.toCharArray();
        this.channelId = channelId;
        this.intents = intents;
        this.transport = Objects.requireNonNull(transport, "transport");
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
    public void start() {
        SyncListener current = listener;
        if (current != null) {
            gateway = new DiscordGateway(gatewayUrl, tokenString(), intents, this, current);
            gateway.connect();
        }
    }

    /**
     * Returns the token as a String for the HTTP/WebSocket APIs.
     * The token is only wiped by {@link #stop()}, never while the sink is running.
     */
    private String tokenString() {
        for (char c : token) {
            if (c != '\0') {
                return new String(token);
            }
        }
        throw new IllegalStateException("Discord token is no longer available; the sink was stopped");
    }

    @Override
    public void stop() {
        DiscordGateway current = gateway;
        if (current != null) {
            current.close();
        }
        gateway = null;
        Arrays.fill(token, '\0');
    }

    @Override
    public void send(Message message) throws Exception {
        String body = new JSONObject().put("content", message.text()).toString();
        String response = transport.post(
            apiBase + "/channels/" + channelId + "/messages",
            Map.of("Authorization", "Bot " + tokenString(),
                "Content-Type", "application/json; charset=utf-8"),
            body);
        JSONObject created = new JSONObject(response);
        if (created.optInt("code", 0) != 0 || created.optString("id", "").isEmpty()) {
            throw new IllegalStateException("discord send failed: " + response);
        }
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }
}
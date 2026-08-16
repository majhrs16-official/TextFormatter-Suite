package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import org.json.JSONObject;

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
    private final String token;
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
        this.token = Objects.requireNonNull(token, "token");
        this.channelId = channelId;
        this.intents = intents;
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public String name() {
        return "discord";
    }

    @Override
    public void start() {
        SyncListener current = listener;
        if (current != null) {
            gateway = new DiscordGateway(gatewayUrl, token, intents, this, current);
            gateway.connect();
        }
    }

    @Override
    public void stop() {
        DiscordGateway current = gateway;
        if (current != null) {
            current.close();
        }
        gateway = null;
    }

    @Override
    public void send(Message message) throws Exception {
        String body = new JSONObject().put("content", message.text()).toString();
        String response = transport.post(
            apiBase + "/channels/" + channelId + "/messages",
            Map.of("Authorization", "Bot " + token,
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
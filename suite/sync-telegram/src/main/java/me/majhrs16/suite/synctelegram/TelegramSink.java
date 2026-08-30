package me.majhrs16.suite.synctelegram;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.transport.Transport;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.Objects;

/**
 * Telegram Bot API edge. Outbound posts {@code sendMessage} to the bot API;
 * inbound long-polls {@code getUpdates} and feeds each incoming text message
 * to {@link SyncListener}. Offset watermark prevents duplicate deliveries.
 */
public final class TelegramSink implements SyncSink {

    private final String baseUrl;
    private final long chatId;
    private final Transport transport;
    private volatile SyncListener listener;
    private volatile long lastUpdateId;

    public TelegramSink(String token, long chatId, Transport transport) {
        this.baseUrl = "https://api.telegram.org/bot" + Objects.requireNonNull(token);
        this.chatId = chatId;
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public void start() {
        // Outbound is one-shot; inbound is long-polled via poll().
    }

    @Override
    public void stop() {
        // Nothing to release; long-poll loop is driven by the caller.
    }

    @Override
    public void send(Message message) throws Exception {
        String url = Transport.withQuery(baseUrl + "/sendMessage", Map.of(
            "chat_id", String.valueOf(chatId),
            "text", message.text()));
        String body = transport.get(url);
        JSONObject ok = new JSONObject(body);
        if (!ok.optBoolean("ok", false)) {
            throw new IllegalStateException("telegram send failed: " + body);
        }
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    /**
     * Fetches one long-poll batch and delivers inbound messages in order.
     *
     * @return the number of messages delivered this poll.
     */
    public int poll() throws Exception {
        String url = baseUrl + "/getUpdates?timeout=1"
            + (lastUpdateId == 0 ? "" : "&offset=" + (lastUpdateId + 1));
        JSONObject body = new JSONObject(transport.get(url));
        if (!body.optBoolean("ok", false)) {
            return 0;
        }
        JSONArray updates = body.optJSONArray("result");
        int delivered = 0;
        for (int i = 0; i < updates.length(); i++) {
            JSONObject update = updates.getJSONObject(i);
            long updateId = update.getLong("update_id");
            lastUpdateId = Math.max(lastUpdateId, updateId);
            JSONObject message = update.optJSONObject("message");
            if (message == null) {
                continue;
            }
            long fromChat = message.optJSONObject("chat") != null
                ? message.getJSONObject("chat").optLong("id", 0)
                : 0;
            if (fromChat != chatId) {
                continue;
            }
            String text = message.optString("text", "");
            if (text.isBlank()) {
                continue;
            }
            String from = message.optJSONObject("from") != null
                ? message.getJSONObject("from").optString("first_name", "Telegram")
                : "Telegram";
            SyncListener current = listener;
            if (current != null) {
                current.onMessage(this, Message.builder()
                    .sender(Actor.unknown(from))
                    .text(text)
                    .channel("telegram")
                    .build());
                delivered++;
            }
        }
        return delivered;
    }
}

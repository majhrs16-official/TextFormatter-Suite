package me.majhrs16.suite.synctelegram;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
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
import me.majhrs16.suite.transport.Transport;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Telegram Bot API edge. Outbound posts {@code sendMessage} to the bot API;
 * inbound long-polls {@code getUpdates} and feeds each incoming text message
 * to {@link SyncListener}. Offset watermark prevents duplicate deliveries.
 *
 * <p>The sink publishes the official least common multiple plus its native
 * surface. Native actions require an explicit {@code chat} argument and a
 * {@link Principal.Program}; {@link Principal.Official} only reaches the
 * LCM. Every Telegram failure is mapped to an explicit {@link SyncResult}.</p>
 */
public final class TelegramSink implements SyncSink {

    private static final String CHAT_TITLE_SET = "chat.title.set";
    private static final String CHAT_LEAVE = "chat.leave";
    private static final String MESSAGE_DELETE = "message.delete";
    private static final String MESSAGE_EDIT = "message.edit";
    private static final String CHAT_MEMBER_BAN = "chat.member.ban";
    private static final String CHAT_MEMBER_UNBAN = "chat.member.unban";

    private static final Set<String> NATIVE_ACTIONS = Set.of(
        CHAT_TITLE_SET, CHAT_LEAVE, MESSAGE_DELETE, MESSAGE_EDIT,
        CHAT_MEMBER_BAN, CHAT_MEMBER_UNBAN);

    private static final SyncSurface SURFACE = buildSurface();

    private final TelegramApi api;
    private final long chatId;
    private volatile SyncListener listener;
    private volatile long lastUpdateId;

    public TelegramSink(String token, long chatId, Transport transport) {
        this.api = new TelegramApi(token, transport);
        this.chatId = chatId;
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public SyncSurface surface() {
        return SURFACE;
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

        JSONObject params;
        try {
            params = paramsOf(call);
        } catch (IllegalArgumentException invalid) {
            return new SyncResult.InvalidArgument(invalid.getMessage());
        }
        try {
            return map(api.post(methodOf(call.action()), params));
        } catch (IOException failure) {
            return new SyncResult.HostFailure(describe(failure));
        }
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
        // Outbound is one-shot; inbound is long-polled via poll().
    }

    /**
     * Stops accepting work. The token is not destroyed here: the bus may stop
     * and restart the same sink during a reload. The terminal release happens
     * in {@link #close()}, which the module activator calls on deactivation.
     */
    @Override
    public void stop() {
        // Nothing to release; see close().
    }

    /**
     * Releases the token buffer. The sink cannot be used afterwards; the
     * activator calls it once the package is deactivated (ADR-003 §4).
     */
    public void close() {
        api.close();
    }

    @Override
    public void send(Message message) throws Exception {
        JSONObject params = new JSONObject()
            .put("chat_id", chatId)
            .put("text", message.text());
        JSONObject body = api.post("sendMessage", params);
        if (!body.optBoolean("ok", false)) {
            throw new IllegalStateException(
                "telegram send failed: " + body.optString("description", body.toString()));
        }
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    /**
     * Fetches one long-poll batch and delivers inbound messages in order.
     *
     * @return the number of messages delivered this poll
     * @throws Exception when the Bot API rejects the request or the network
     *                   fails; a rejected poll is never reported as zero
     *                   messages
     */
    public int poll() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timeout", "1");
        if (lastUpdateId != 0) {
            params.put("offset", String.valueOf(lastUpdateId + 1));
        }
        JSONObject body = api.get("getUpdates", params);
        if (!body.optBoolean("ok", false)) {
            throw new IllegalStateException(
                "telegram getUpdates failed: " + body.optString("description", body.toString()));
        }
        JSONArray updates = body.optJSONArray("result");
        if (updates == null) {
            return 0;
        }
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

    private static SyncSurface buildSurface() {
        SyncSurface lcm = SyncSurface.lcm("telegram", SyncSurface.CURRENT_CONTRACT_VERSION);
        Set<SyncActionSpec> actions = new LinkedHashSet<>(lcm.actions());
        actions.add(new SyncActionSpec(
            CHAT_TITLE_SET,
            ActionKind.WRITE,
            List.of(
                SyncParamSpec.required("chat", SyncValueType.CHANNEL_REF),
                SyncParamSpec.required("title", SyncValueType.STRING)),
            null,
            true,
            15_000));
        actions.add(new SyncActionSpec(
            CHAT_LEAVE,
            ActionKind.DESTRUCTIVE,
            List.of(
                SyncParamSpec.required("chat", SyncValueType.CHANNEL_REF)),
            null,
            false,
            15_000));
        actions.add(new SyncActionSpec(
            MESSAGE_DELETE,
            ActionKind.DESTRUCTIVE,
            List.of(
                SyncParamSpec.required("chat", SyncValueType.CHANNEL_REF),
                SyncParamSpec.required("message", SyncValueType.INTEGER)),
            null,
            false,
            15_000));
        actions.add(new SyncActionSpec(
            MESSAGE_EDIT,
            ActionKind.WRITE,
            List.of(
                SyncParamSpec.required("chat", SyncValueType.CHANNEL_REF),
                SyncParamSpec.required("message", SyncValueType.INTEGER),
                SyncParamSpec.required("text", SyncValueType.STRING)),
            null,
            true,
            15_000));
        actions.add(new SyncActionSpec(
            CHAT_MEMBER_BAN,
            ActionKind.MODERATION,
            List.of(
                SyncParamSpec.required("chat", SyncValueType.CHANNEL_REF),
                SyncParamSpec.required("user", SyncValueType.ACTOR_REF),
                SyncParamSpec.optional("revokeMessages", SyncValueType.BOOLEAN)),
            null,
            true,
            15_000));
        actions.add(new SyncActionSpec(
            CHAT_MEMBER_UNBAN,
            ActionKind.MODERATION,
            List.of(
                SyncParamSpec.required("chat", SyncValueType.CHANNEL_REF),
                SyncParamSpec.required("user", SyncValueType.ACTOR_REF),
                SyncParamSpec.optional("onlyIfBanned", SyncValueType.BOOLEAN)),
            null,
            true,
            15_000));
        return new SyncSurface("telegram", SyncSurface.CURRENT_CONTRACT_VERSION, actions);
    }

    private static String methodOf(String action) {
        return switch (action) {
            case CHAT_TITLE_SET -> "setChatTitle";
            case CHAT_LEAVE -> "leaveChat";
            case MESSAGE_DELETE -> "deleteMessage";
            case MESSAGE_EDIT -> "editMessageText";
            case CHAT_MEMBER_BAN -> "banChatMember";
            case CHAT_MEMBER_UNBAN -> "unbanChatMember";
            default -> throw new IllegalArgumentException(
                "unsupported Telegram action: " + action);
        };
    }

    private static JSONObject paramsOf(SyncCall call) {
        return switch (call.action()) {
            case CHAT_TITLE_SET -> new JSONObject()
                .put("chat_id", text(call, "chat"))
                .put("title", text(call, "title"));
            case CHAT_LEAVE -> new JSONObject()
                .put("chat_id", text(call, "chat"));
            case MESSAGE_DELETE -> new JSONObject()
                .put("chat_id", text(call, "chat"))
                .put("message_id", id(call, "message"));
            case MESSAGE_EDIT -> new JSONObject()
                .put("chat_id", text(call, "chat"))
                .put("message_id", id(call, "message"))
                .put("text", text(call, "text"));
            case CHAT_MEMBER_BAN -> {
                JSONObject params = new JSONObject()
                    .put("chat_id", text(call, "chat"))
                    .put("user_id", id(call, "user"));
                flag(call, "revokeMessages").ifPresent(
                    value -> params.put("revoke_messages", value));
                yield params;
            }
            case CHAT_MEMBER_UNBAN -> {
                JSONObject params = new JSONObject()
                    .put("chat_id", text(call, "chat"))
                    .put("user_id", id(call, "user"));
                flag(call, "onlyIfBanned").ifPresent(
                    value -> params.put("only_if_banned", value));
                yield params;
            }
            default -> throw new IllegalArgumentException(
                "unsupported Telegram action: " + call.action());
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

    /**
     * Reads a positive Telegram id, accepting the numeric string an
     * {@code ACTOR_REF} carries or a JSON number.
     */
    private static long id(SyncCall call, String name) {
        Object value = call.args().get(name);
        long parsed;
        if (value instanceof Number number) {
            parsed = number.longValue();
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                parsed = Long.parseLong(text.trim());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(
                    "'" + name + "' must be a numeric Telegram id");
            }
        } else {
            throw new IllegalArgumentException(
                "'" + name + "' must be a numeric Telegram id");
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(
                "'" + name + "' must be a positive Telegram id");
        }
        return parsed;
    }

    private static Optional<Boolean> flag(SyncCall call, String name) {
        Object value = call.args().get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException("'" + name + "' must be a boolean");
        }
        return Optional.of(flag);
    }

    private static SyncResult map(JSONObject body) {
        if (body.optBoolean("ok", false)) {
            return new SyncResult.Ok(null);
        }
        int code = body.optInt("error_code", 0);
        String description = body.optString("description", "telegram request failed");
        return switch (code) {
            // Telegram reports a missing message/chat as 400, not 404.
            case 400 -> description.toLowerCase(Locale.ROOT).contains("not found")
                ? new SyncResult.NotFound(description)
                : new SyncResult.InvalidArgument(description);
            case 401, 403 -> new SyncResult.HostFailure(
                "Telegram denied the action: " + description);
            case 404 -> new SyncResult.NotFound(description);
            case 409 -> new SyncResult.Conflict(description);
            case 429 -> new SyncResult.RateLimited(retryAfterMillis(body));
            default -> new SyncResult.HostFailure(description);
        };
    }

    private static long retryAfterMillis(JSONObject body) {
        JSONObject parameters = body.optJSONObject("parameters");
        double seconds = parameters == null ? 0 : parameters.optDouble("retry_after", 0);
        return seconds > 0 ? Math.round(seconds * 1000) : 0;
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }
}

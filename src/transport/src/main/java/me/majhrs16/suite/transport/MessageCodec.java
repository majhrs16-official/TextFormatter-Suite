package me.majhrs16.suite.transport;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Channel;
import me.majhrs16.suite.api.message.ColorMode;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Unified JSON codec for {@link Message} used by all sync edges (HTTP, TCP, UDP, Discord, Telegram).
 *
 * <p>This codec carries ALL fields needed to re-enter the engine pipeline on the receiving side.
 * It is fully lossless for cross-server synchronization.</p>
 */
public final class MessageCodec {

    private MessageCodec() {
    }

    // JSON field names (constants for consistency)
    private static final String F_ID = "id";
    private static final String F_TYPE = "type";
    private static final String F_CHANNEL = "channel";
    private static final String F_SENDER = "sender";
    private static final String F_DIRECTION = "direction";
    private static final String F_DIRECTION_QUALIFIER = "directionQualifier";
    private static final String F_EXPLICIT_RECIPIENTS = "explicitRecipients";
    private static final String F_LANG_SOURCE = "langSource";
    private static final String F_LANG_TARGET = "langTarget";
    private static final String F_TRANSLATE = "translate";
    private static final String F_CANCELLED = "cancelled";
    private static final String F_TEXTS = "texts";
    private static final String F_SOUNDS = "sounds";
    private static final String F_TOOLTIPS = "tooltips";
    private static final String F_COLOR_MODE = "colorMode";
    private static final String F_FORMAT_PAPI = "formatPapi";
    private static final String F_SHOW = "show";
    private static final String F_SLEEP_MILLIS = "sleepMillis";
    private static final String F_RESOLVED_SOURCE_LANG = "resolvedSourceLang";

    /**
     * Encodes a {@link Message} to a JSON object.
     *
     * @param message the message to encode
     * @return JSON object with all wire fields
     */
    public static JSONObject toJson(Message message) {
        JSONObject json = new JSONObject();
        json.put(F_ID, message.id().toString());
        json.put(F_TYPE, message.type().name());
        json.put(F_CHANNEL, message.channel() == null ? "chat" : message.channel());

        // Sender
        JSONObject sender = new JSONObject();
        sender.put("name", message.sender().name());
        sender.put("uuid", message.sender().uuid() == null ? "" : message.sender().uuid().toString());
        sender.put("kind", message.sender().kind().name());
        json.put(F_SENDER, sender);

        // Direction (full semantics)
        Direction dir = message.direction();
        json.put(F_DIRECTION, dir.kind().name());
        if (dir.qualifier() != null) {
            json.put(F_DIRECTION_QUALIFIER, dir.qualifier());
        }
        if (dir.recipients() != null && dir.recipients().length > 0) {
            JSONArray recipients = new JSONArray();
            for (Actor r : dir.recipients()) {
                JSONObject rJson = new JSONObject();
                rJson.put("name", r.name());
                rJson.put("uuid", r.uuid() == null ? "" : r.uuid().toString());
                rJson.put("kind", r.kind().name());
                recipients.put(rJson);
            }
            json.put(F_EXPLICIT_RECIPIENTS, recipients);
        }

        json.put(F_LANG_SOURCE, message.langSource().code());
        json.put(F_LANG_TARGET, message.langTarget().code());
        json.put(F_TRANSLATE, message.shouldTranslate());
        json.put(F_CANCELLED, message.isCancelled());

        // Texts
        JSONArray texts = new JSONArray();
        for (String text : message.texts()) {
            texts.put(text);
        }
        json.put(F_TEXTS, texts);

        // Sounds (stored as sound names in Message)
        if (message.sounds() != null && message.sounds().length > 0) {
            JSONArray sounds = new JSONArray();
            for (String soundName : message.sounds()) {
                sounds.put(soundName);
            }
            json.put(F_SOUNDS, sounds);
        }

        // Tooltips
        if (message.toolTips() != null && !message.toolTips().isEmpty()) {
            JSONArray tooltips = new JSONArray();
            for (String tip : message.toolTips().texts()) {
                tooltips.put(tip);
            }
            json.put(F_TOOLTIPS, tooltips);
        }

        // Color mode
        json.put(F_COLOR_MODE, message.colorMode().name());

        // Format PAPI
        json.put(F_FORMAT_PAPI, message.formatPapi());

        // Show
        json.put(F_SHOW, message.isShown());

        // Sleep millis
        json.put(F_SLEEP_MILLIS, message.sleepMillis());

        // Resolved source language
        if (message.resolvedSourceLanguage() != null) {
            json.put(F_RESOLVED_SOURCE_LANG, message.resolvedSourceLanguage().code());
        }

        return json;
    }

    /**
     * Serializes a {@link Message} to a compact JSON string.
     *
     * @param message the message to encode
     * @return compact JSON string
     */
    public static String toJsonString(Message message) {
        return toJson(message).toString();
    }

    /**
     * Decodes a {@link Message} from a JSON string.
     *
     * @param raw JSON string
     * @return decoded message
     */
    public static Message fromJson(String raw) {
        JSONObject json = new JSONObject(raw);
        UUID id = json.has(F_ID) && !json.isNull(F_ID)
            ? UUID.fromString(json.getString(F_ID))
            : UUID.randomUUID();

        // Sender
        JSONObject senderJson = json.optJSONObject(F_SENDER);
        Actor sender = senderJson == null
            ? Actor.unknown("REMOTE")
            : new Actor(
                senderJson.optString("uuid", "").isEmpty()
                    ? null : UUID.fromString(senderJson.getString("uuid")),
                senderJson.optString("name", "REMOTE"),
                Actor.ActorKind.valueOf(senderJson.optString("kind", "UNKNOWN")),
                null, null);

        // Direction (full semantics)
        String direction = json.optString(F_DIRECTION, "OTHERS");
        Direction.Kind kind = Direction.Kind.valueOf(direction);
        String qualifier = json.optString(F_DIRECTION_QUALIFIER, null);

        Actor[] explicitRecipients = new Actor[0];
        if (json.has(F_EXPLICIT_RECIPIENTS)) {
            JSONArray recipientsJson = json.getJSONArray(F_EXPLICIT_RECIPIENTS);
            explicitRecipients = new Actor[recipientsJson.length()];
            for (int i = 0; i < recipientsJson.length(); i++) {
                JSONObject rJson = recipientsJson.getJSONObject(i);
                explicitRecipients[i] = new Actor(
                    rJson.optString("uuid", "").isEmpty()
                        ? null : UUID.fromString(rJson.getString("uuid")),
                    rJson.optString("name", "REMOTE"),
                    Actor.ActorKind.valueOf(rJson.optString("kind", "UNKNOWN")),
                    null, null);
            }
        }

        Direction dir = switch (kind) {
            case INITIATOR -> Direction.initiator();
            case ALL -> Direction.all();
            case CONSOLE -> Direction.console();
            case SPECIFIC -> Direction.specific(Channel.CHAT, explicitRecipients);
            case PERMISSION -> Direction.permission(qualifier);
            case WORLD -> Direction.world(qualifier);
            case RADIUS -> Direction.radius(qualifier == null ? 0 : Double.parseDouble(qualifier));
            default -> Direction.others();
        };

        // Texts
        JSONArray texts = json.optJSONArray(F_TEXTS);
        String[] textArray;
        if (texts == null) {
            textArray = new String[0];
        } else {
            textArray = new String[texts.length()];
            for (int i = 0; i < texts.length(); i++) {
                Object obj = texts.opt(i);
                textArray[i] = obj == null ? "" : String.valueOf(obj);
            }
        }
        Formats formats = Formats.of(textArray);

        // Sounds
        String[] sounds = new String[0];
        if (json.has(F_SOUNDS)) {
            JSONArray soundsJson = json.getJSONArray(F_SOUNDS);
            sounds = new String[soundsJson.length()];
            for (int i = 0; i < soundsJson.length(); i++) {
                sounds[i] = soundsJson.getString(i);
            }
        }

        // Tooltips
        Formats tooltips = Formats.empty();
        if (json.has(F_TOOLTIPS)) {
            JSONArray tipsJson = json.getJSONArray(F_TOOLTIPS);
            String[] tipArray = new String[tipsJson.length()];
            for (int i = 0; i < tipsJson.length(); i++) {
                tipArray[i] = tipsJson.getString(i);
            }
            tooltips = Formats.of(tipArray);
        }

        // Color mode
        ColorMode colorMode = ColorMode.valueOf(json.optString(F_COLOR_MODE, "BY_PERMISSION"));

        // Format PAPI
        boolean formatPapi = json.optBoolean(F_FORMAT_PAPI, false);

        // Show
        boolean show = json.optBoolean(F_SHOW, true);

        // Sleep millis
        long sleepMillis = json.optLong(F_SLEEP_MILLIS, 0);

        // Resolved source language
        Language resolvedSourceLang = null;
        if (json.has(F_RESOLVED_SOURCE_LANG)) {
            resolvedSourceLang = Language.of(json.getString(F_RESOLVED_SOURCE_LANG)).orElse(null);
        }

        Language source = Language.of(json.optString(F_LANG_SOURCE, "auto")).orElse(Language.AUTO);
        Language target = Language.of(json.optString(F_LANG_TARGET, "auto")).orElse(Language.AUTO);

        return Message.builder()
            .id(id)
            .type(MessageType.valueOf(json.optString(F_TYPE, "CHAT")))
            .sender(sender)
            .direction(dir)
            .messages(formats)
            .channel(json.optString(F_CHANNEL, "chat"))
            .langSource(source)
            .langTarget(target)
            .translate(json.optBoolean(F_TRANSLATE, true))
            .cancelled(json.optBoolean(F_CANCELLED, false))
            .sounds(sounds)
            .toolTips(tooltips)
            .colorMode(colorMode)
            .formatPapi(formatPapi)
            .show(show)
            .sleepMillis(sleepMillis)
            .resolvedSourceLanguage(resolvedSourceLang)
            .build();
    }

    /**
     * Decodes a {@link Message} from a JSON string (string variant).
     *
     * @param raw JSON string
     * @return decoded message
     */
    public static Message fromJsonString(String raw) {
        return fromJson(raw);
    }

    /**
     * Decodes a {@link Message} from a {@link JSONObject}.
     *
     * @param json JSON object
     * @return decoded message
     */
    public static Message fromJson(JSONObject json) {
        return fromJson(json.toString());
    }
}
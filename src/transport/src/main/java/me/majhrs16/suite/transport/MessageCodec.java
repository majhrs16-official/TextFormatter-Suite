package me.majhrs16.suite.transport;

import me.majhrs16.suite.api.message.Actor;
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
 * <p>This codec carries all fields needed to re-enter the engine pipeline on the receiving side.
 * It is lossless for the fields the engine needs to re-enter the pipeline; rendering specifics
 * live server-side.</p>
 */
public final class MessageCodec {

    private MessageCodec() {
    }

    /**
     * Encodes a {@link Message} to a JSON object.
     *
     * @param message the message to encode
     * @return JSON object with all wire fields
     */
    public static JSONObject toJson(Message message) {
        JSONObject json = new JSONObject();
        json.put("id", message.id().toString());
        json.put("type", message.type().name());
        json.put("channel", message.channel() == null ? "chat" : message.channel());

        JSONObject sender = new JSONObject();
        sender.put("name", message.sender().name());
        sender.put("uuid", message.sender().uuid() == null ? "" : message.sender().uuid().toString());
        sender.put("kind", message.sender().kind().name());
        json.put("sender", sender);

        json.put("direction", message.direction().kind().name());
        json.put("langSource", message.langSource().code());
        json.put("langTarget", message.langTarget().code());
        json.put("translate", message.shouldTranslate());
        json.put("cancelled", message.isCancelled());

        JSONArray texts = new JSONArray();
        for (String text : message.texts()) {
            texts.put(text);
        }
        json.put("texts", texts);
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
        UUID id = json.has("id") && !json.isNull("id")
            ? UUID.fromString(json.getString("id"))
            : UUID.randomUUID();

        JSONObject senderJson = json.optJSONObject("sender");
        Actor sender = senderJson == null
            ? Actor.unknown("REMOTE")
            : new Actor(
                senderJson.optString("uuid", "").isEmpty()
                    ? null : UUID.fromString(senderJson.getString("uuid")),
                senderJson.optString("name", "REMOTE"),
                Actor.ActorKind.valueOf(senderJson.optString("kind", "UNKNOWN")),
                null, null);

        String direction = json.optString("direction", "OTHERS");
        Direction dir = switch (direction) {
            case "INITIATOR" -> Direction.initiator();
            case "ALL" -> Direction.all();
            case "CONSOLE" -> Direction.console();
            default -> Direction.others();
        };

        JSONArray texts = json.optJSONArray("texts");
        Formats formats = texts == null
            ? Formats.empty()
            : Formats.of(texts.toList().toArray(new String[0]));

        Language source = Language.of(json.optString("langSource", "auto")).orElse(Language.AUTO);
        Language target = Language.of(json.optString("langTarget", "auto")).orElse(Language.AUTO);

        return Message.builder()
            .id(id)
            .type(MessageType.valueOf(json.optString("type", "CHAT")))
            .sender(sender)
            .direction(dir)
            .messages(Formats.of(texts == null ? new String[0] : texts.toList().toArray(new String[0])))
            .channel(json.optString("channel", "chat"))
            .langSource(source)
            .langTarget(target)
            .translate(json.optBoolean("translate", true))
            .cancelled(json.optBoolean("cancelled", false))
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

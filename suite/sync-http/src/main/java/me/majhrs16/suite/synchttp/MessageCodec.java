package me.majhrs16.suite.synchttp;

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
 * Minimal lossless-enough JSON codec for {@link Message} over the wire.
 * Only the fields the engine needs to re-enter the pipeline are carried;
 * rendering specifics live server-side.
 */
public final class MessageCodec {

    private MessageCodec() {
    }

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
            .messages(formats)
            .channel(json.optString("channel", "chat"))
            .langSource(source)
            .langTarget(target)
            .translate(json.optBoolean("translate", true))
            .cancelled(json.optBoolean("cancelled", false))
            .build();
    }
}
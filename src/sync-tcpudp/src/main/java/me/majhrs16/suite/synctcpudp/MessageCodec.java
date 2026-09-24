package me.majhrs16.suite.synctcpudp;

import me.majhrs16.suite.api.message.Message;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Minimal JSON codec for {@link Message} shared by the TCP/UDP edge. Mirrors
 * the HTTP edge codec so all bordes speak the same wire format.
 */
public final class MessageCodec {

    private MessageCodec() {
    }

    public static String toJson(Message message) {
        JSONObject json = new JSONObject();
        json.put("id", message.id().toString());
        json.put("type", message.type().name());
        json.put("channel", message.channel() == null ? "chat" : message.channel());
        json.put("sender", message.sender().name());
        json.put("direction", message.direction().kind().name());
        json.put("langSource", message.langSource().code());
        json.put("langTarget", message.langTarget().code());
        json.put("translate", message.shouldTranslate());

        JSONArray texts = new JSONArray();
        for (String text : message.texts()) {
            texts.put(text);
        }
        json.put("texts", texts);
        return json.toString();
    }

    public static Message fromJson(String raw) {
        JSONObject json = new JSONObject(raw);
        JSONArray texts = json.optJSONArray("texts");
        String[] contents = texts == null
            ? new String[0]
            : texts.toList().toArray(new String[0]);
        return Message.builder()
            .id(json.has("id") ? UUID.fromString(json.getString("id")) : UUID.randomUUID())
            .type(me.majhrs16.suite.api.message.MessageType.valueOf(
                json.optString("type", "CHAT")))
            .sender(me.majhrs16.suite.api.message.Actor.unknown(
                json.optString("sender", "REMOTE")))
            .direction(me.majhrs16.suite.api.message.Direction.others())
            .texts(contents)
            .channel(json.optString("channel", "chat"))
            .translate(json.optBoolean("translate", true))
            .build();
    }
}
package me.majhrs16.cht.core.message;

/**
 * Serialization bridge for {@link Message}.
 *
 * <p>The old engine serialized the from/to pair into a JSON structure consumed
 * by CoT ({@code Message.toJson/fromJson}). Under the new model a message is a
 * single unit, so the JSON carries the message fields plus its sender and
 * direction. The router uses this to persist or move messages across the
 * console/broadcast boundary (the CoT replacement) without coupling the core
 * to a JSON library.</p>
 */
public interface JsonCodec {

    /**
     * @param message the message to serialize.
     * @return its JSON string representation.
     */
    String write(Message message);

    /**
     * @param json the serialized message.
     * @return the deserialized message, or {@code null} when unparseable.
     */
    Message read(String json);
}
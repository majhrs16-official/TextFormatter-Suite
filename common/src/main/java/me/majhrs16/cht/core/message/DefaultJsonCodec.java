package me.majhrs16.cht.core.message;

import me.majhrs16.cht.core.language.Language;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minimal {@link JsonCodec} with zero external dependencies.
 *
 * <p>Serializes the atomic {@link Message} into a compact JSON object used to
 * pass messages across the CoT boundaries (broadcast, console, Discord). Parses
 * back into a {@link Message}. Because colors and MiniMessage span markup may
 * contain quote and backslash characters, every string field is JSON-escaped on
 * both directions.</p>
 *
 * <pre>{@code
 * {
 *   "type":"CHAT", "sender":"Majhrs", "senderKind":"PLAYER",
 *   "direction":"OTHERS", "langSource":"es", "langTarget":"en",
 *   "texts":["hola"], "formats":[], "toolTips":[], "sounds":[],
 *   "colorMode":"BY_PERMISSION", "translate":true, "show":true, "papi":true
 * }
 * }</pre>
 */
public final class DefaultJsonCodec implements JsonCodec {

    @Override
    public String write(Message message) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"type\":").append(quote(message.type().name()));
        json.append(",\"sender\":").append(quote(message.sender().name()));
        json.append(",\"senderKind\":").append(quote(message.sender().kind().name()));
        json.append(",\"senderUuid\":").append(quote(uuid(message.sender())));
        json.append(",\"direction\":").append(quote(message.direction().kind().name()));
        json.append(",\"directionChannel\":").append(quote(message.direction().channel().name()));
        json.append(",\"langSource\":").append(quote(message.langSource().code()));
        json.append(",\"langTarget\":").append(quote(message.langTarget().code()));
        json.append(",\"texts\":").append(stringArray(message.messages().texts()));
        json.append(",\"formats\":").append(stringArray(message.messages().formats()));
        json.append(",\"toolTips\":").append(stringArray(message.toolTips().texts()));
        json.append(",\"sounds\":").append(stringArray(message.sounds()));
        json.append(",\"colorMode\":").append(quote(message.colorMode().name()));
        json.append(",\"translate\":").append(message.shouldTranslate());
        json.append(",\"show\":").append(message.isShown());
        json.append(",\"papi\":").append(message.formatPapi());
        json.append("}");
        return json.toString();
    }

    @Override
    public Message read(String json) {
        if (json == null) {
            return null;
        }
        String body = JSON.trimBraces(json);
        java.util.Map<String, String> fields = JSON.parseFields(body);

        Message.Builder builder = Message.builder()
            .type(safeEnum(MessageType.class, fields.get("type"), MessageType.CUSTOM))
            .sender(new Actor(
                safeUuid(fields.get("senderUuid")),
                or(fields, "sender", "UNKNOWN"),
                safeEnum(Actor.ActorKind.class, fields.get("senderKind"), Actor.ActorKind.UNKNOWN),
                lang(fields.get("langSource")), null))
            .direction(safeDirection(fields.get("direction"), fields.get("directionChannel")))
            .langSource(lang(fields.get("langSource")))
            .langTarget(lang(fields.get("langTarget")))
            .messages(new Formats(
                split(fields.get("texts")),
                split(fields.get("formats"))))
            .toolTips(new Formats(split(fields.get("toolTips")), new String[0]))
            .sounds(split(fields.get("sounds")))
            .colorMode(safeEnum(ColorMode.class, fields.get("colorMode"), ColorMode.BY_PERMISSION))
            .translate(boolOf(fields.get("translate"), true))
            .show(boolOf(fields.get("show"), true))
            .formatPapi(boolOf(fields.get("papi"), true));

        return builder.build();
    }

    // -- helpers -------------------------------------------------------------

    private static String uuid(Actor actor) {
        return actor.uuid() != null ? actor.uuid().toString() : "";
    }

    private static UUID safeUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(clean(raw));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Language lang(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Language.AUTO;
        }
        return Language.of(clean(raw)).orElse(Language.AUTO);
    }

    private static Direction safeDirection(String kind, String channel) {
        Direction.Kind directionKind = safeEnum(Direction.Kind.class, kind, Direction.Kind.OTHERS);
        me.majhrs16.cht.core.player.Channel ch = safeEnum(
            me.majhrs16.cht.core.player.Channel.class, channel, me.majhrs16.cht.core.player.Channel.CHAT);
        return new Direction(directionKind, ch, null, new Actor[0]);
    }

    private static String or(java.util.Map<String, String> fields, String key, String fallback) {
        String value = fields.get(key);
        return value == null || value.isEmpty() ? fallback : clean(value);
    }

    private static boolean boolOf(String raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        return Boolean.parseBoolean(clean(raw));
    }

    /** Strips the surrounding JSON quotes (if any) and the escaping. */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String unquoted = JSON.stripQuotes(raw);
        return unquoted.isEmpty() ? raw : unescape(unquoted);
    }

    private static String[] split(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new String[0];
        }
        String body = raw.trim();
        if (body.startsWith("[") && body.endsWith("]") && body.length() >= 2) {
            body = body.substring(1, body.length() - 1);
        }
        body = body.trim();
        if (body.isEmpty()) {
            return new String[0];
        }
        List<String> items = new ArrayList<>();
        int index = 0;
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        while (index < body.length()) {
            char c = body.charAt(index);
            if (inString) {
                current.append(c);
                if (c == '\\') {
                    if (index + 1 < body.length()) {
                        current.append(body.charAt(index + 1));
                        index += 2;
                        continue;
                    }
                } else if (c == '"') {
                    inString = false;
                }
                index++;
                continue;
            }
            if (c == '"') {
                inString = true;
                current.append(c);
            } else if (c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ']' || c == '}') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                items.add(clean(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
            index++;
        }
        if (current.length() > 0) {
            items.add(clean(current.toString()));
        }
        return items.toArray(new String[0]);
    }

    private static String stringArray(String[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(values[i])).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static <E extends Enum<E>> E safeEnum(Class<E> type, String name, E fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, clean(name));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Tiny JSON helper without pulling a parser dependency. */
    private static final class JSON {
        private static String trimBraces(String json) {
            String trimmed = json.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            return trimmed;
        }

        private static java.util.Map<String, String> parseFields(String body) {
            java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
            int index = 0;
            while (index < body.length()) {
                while (index < body.length()) {
                    char c = body.charAt(index);
                    if (c == ',' || c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                        index++;
                    } else {
                        break;
                    }
                }
                if (index >= body.length()) {
                    break;
                }
                int colon = nextTopLevel(body, index, ':');
                if (colon < 0) {
                    break;
                }
                String key = stripQuotes(body.substring(index, colon).trim());
                Value value = readValue(body, colon + 1);
                if (value == null || value.end <= index) {
                    break;
                }
                fields.put(key, value.raw);
                index = value.end;
            }
            return fields;
        }

        /** Reads a JSON value starting at or after `from`; returns its raw text. */
        private static Value readValue(String text, int from) {
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == ' ') {
                    continue;
                }
                if (c == '"') {
                    int end = findStringEnd(text, i);
                    if (end < 0) {
                        return null;
                    }
                    return new Value(text.substring(i, end + 1), end + 1);
                }
                // scalar (true/false/number) or array/object literal
                int end = findScalarEnd(text, i);
                return new Value(text.substring(i, end).trim(), end);
            }
            return null;
        }

        /** End index of a scalar/array/object literal starting at {@code from}. */
        private static int findScalarEnd(String text, int from) {
            int depth = 0;
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '[' || c == '{') {
                    depth++;
                } else if (c == ']' || c == '}') {
                    depth--;
                    if (depth <= 0) {
                        return i + 1;
                    }
                } else if (c == ',' && depth == 0) {
                    return i;
                } else if (c == '"') {
                    i = findStringEnd(text, i);
                }
            }
            return text.length();
        }

        /** Index of the next top-level occurrence of {@code target}. */
        private static int nextTopLevel(String text, int from, char target) {
            int depth = 0;
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '[' || c == '{') {
                    depth++;
                } else if (c == ']' || c == '}') {
                    if (depth > 0) {
                        depth--;
                    }
                } else if (c == '"') {
                    i = findStringEnd(text, i);
                } else if (c == target && depth == 0) {
                    return i;
                }
            }
            return -1;
        }

        private static int findStringEnd(String text, int openQuote) {
            for (int i = openQuote + 1; i < text.length(); i++) {
                if (text.charAt(i) == '\\') {
                    i++;
                } else if (text.charAt(i) == '"') {
                    return i;
                }
            }
            return -1;
        }

        private static String stripQuotes(String value) {
            String trimmed = value.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")
                && trimmed.length() >= 2) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            return trimmed;
        }

        private static final class Value {
            private final String raw;
            private final int end;

            private Value(String raw, int end) {
                this.raw = raw;
                this.end = end;
            }
        }
    }
}
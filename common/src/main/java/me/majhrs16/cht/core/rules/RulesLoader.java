package me.majhrs16.cht.core.rules;

import me.majhrs16.cht.core.message.MessageType;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Loads {@code rules.yml} into ordered {@link RulesEngine.Rule rules}.
 *
 * <p>Format:</p>
 *
 * <pre>{@code
 * rules:
 *   chat_a:
 *     events: [CHAT, SIGN]
 *     conditions:
 *       - "sender().kind() == CONSOLE"
 *     actions:
 *       - "setFormat('remitente_user')"
 * }</pre>
 *
 * <p>Rules are optional; an empty file yields an engine with no rules, which
 * simply passes every message through. This is the native replacement for
 * ConditionalEvents configuration.</p>
 */
public final class RulesLoader {

    private RulesLoader() {
    }

    @SuppressWarnings("unchecked")
    public static List<RulesEngine.Rule> load(InputStream input) throws IOException {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        List<RulesEngine.Rule> rules = new ArrayList<>();
        if (!(parsed instanceof Map)) {
            return rules;
        }
        Object root = ((Map<String, Object>) parsed).get("rules");
        if (!(root instanceof Map)) {
            return rules;
        }
        for (Map.Entry<String, Object> entry :
                ((Map<String, Object>) root).entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> rule = (Map<String, Object>) entry.getValue();
            rules.add(parse(entry.getKey(), rule));
        }
        return rules;
    }

    private static RulesEngine.Rule parse(String name, Map<String, Object> fields) {
        List<MessageType> types = types(fields.get("events"));
        List<String> conditions = strings(fields.get("conditions"));
        List<String> actions = strings(fields.get("actions"));
        return new RulesEngine.Rule(name, types, conditions, actions);
    }

    @SuppressWarnings("unchecked")
    private static List<MessageType> types(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<MessageType> types = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            try {
                types.add(MessageType.valueOf(String.valueOf(item)
                    .toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Unknown type: skip rather than break the whole ruleset.
            }
        }
        return types;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.spi.PluginLogger;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads {@code messages.yml} — every user-visible host string, keyed. This
 * restores the original project's "plugin self-configuration" idea: no UI
 * literal is hardcoded in command handlers; the file is copied on first
 * boot and users edit/retranslate at will.
 *
 * <p>Unknown keys in the file are kept; missing keys fall back to the
 * built-in defaults passed at construction.</p>
 */
public final class MessagesConfig {

    private static final Yaml YAML = new Yaml();

    private final Map<String, String> values;

    private MessagesConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * Builds the message table from {@code dir/messages.yml} over
     * {@code builtIn} defaults. Nested maps flatten to dotted keys
     * ({@code status.channels}). Missing/corrupt file → defaults only.
     */
    public static MessagesConfig load(Path dir, Map<String, String> builtIn) {
        Map<String, String> merged = new HashMap<>(builtIn);
        Path file = dir.resolve("messages.yml");
        if (Files.exists(file)) {
            try {
                Object root = YAML.load(Files.readString(file));
                if (root instanceof Map) {
                    flatten("", root, merged);
                }
            } catch (IOException | RuntimeException ignored) {
                // archivo corrupto: se conservan los defaults.
            }
        }
        return new MessagesConfig(merged);
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object node, Map<String, String> into) {
        if (!(node instanceof Map)) {
            return;
        }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(e.getKey())
                : prefix + "." + e.getKey();
            Object value = ((Map<String, Object>) node).get(String.valueOf(e.getKey()));
            if (value instanceof Map nested) {
                flatten(key, nested, into);
            } else if (value != null) {
                into.put(key, String.valueOf(value));
            }
        }
    }

    /** @return resolved message with {} / %s positional args substituted. */
    public String format(String key, Object... args) {
        return substitute(values.getOrDefault(key, key), args);
    }

    public static String substitute(String template, Object... args) {
        if (args == null || args.length == 0 || template == null) {
            return template == null ? "" : template;
        }
        String out = template;
        for (Object arg : args) {
            int braces = out.indexOf("{}");
            int percent = out.indexOf("%s");
            if (braces < 0 && percent < 0) {
                break;
            }
            int at = braces < 0 ? percent : (percent < 0 ? braces : Math.min(braces, percent));
            out = out.substring(0, at) + arg + out.substring(at + 2);
        }
        return out;
    }

    /** Convenience logger bridge for plugin-side logging of messages.yml keys. */
    public void log(PluginLogger logger, String key, Object... args) {
        logger.info(format(key, args));
    }
}

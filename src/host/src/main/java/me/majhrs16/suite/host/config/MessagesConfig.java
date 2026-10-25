package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.spi.PluginLogger;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads {@code messages.yml} — every user-visible host string, keyed.
 * No UI literal is hardcoded in command handlers; the file is copied on first
 * boot from the module's default resource and users edit/retranslate at will.
 *
 * <p>Unknown keys in the file are kept. If the file is missing or corrupt,
 * the default resource is loaded as fallback (not hardcoded in Java).</p>
 */
public final class MessagesConfig {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private final Map<String, String> values;

    private MessagesConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * Builds the message table from {@code dir/messages.yml}.
     * If the file doesn't exist, loads the default resource from classpath.
     *
     * @param dir configuration directory (e.g. plugin data folder)
     * @param defaultResource classpath path to default messages.yml (e.g. "/defaults/messages.yml")
     */
    public static MessagesConfig load(Path dir, String defaultResource) {
        Map<String, String> merged = new HashMap<>();
        Path file = dir.resolve("messages.yml");

        // First, load defaults from resource (source of truth for defaults)
        loadDefaultsFromResource(defaultResource, merged);

        // Then, overlay with user's file if it exists
        if (Files.exists(file)) {
            try {
                Object root = YAML.load(Files.readString(file));
                if (root instanceof Map) {
                    flatten("", root, merged);
                }
            } catch (IOException | RuntimeException e) {
                // archivo corrupto: se conservan los defaults del recurso
            }
        }
        return new MessagesConfig(merged);
    }

    /**
     * Loads default messages from a classpath resource.
     */
    private static void loadDefaultsFromResource(String resourcePath, Map<String, String> into) {
        try (InputStream in = MessagesConfig.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                Object root = YAML.load(new String(in.readAllBytes()));
                if (root instanceof Map) {
                    flatten("", root, into);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Recurso no disponible: merged queda vacío (keys faltantes → key literal en format())
        }
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
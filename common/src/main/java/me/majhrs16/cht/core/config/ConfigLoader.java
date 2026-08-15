package me.majhrs16.cht.core.config;

import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.ChatMessageType;
import me.majhrs16.cht.core.message.SoundSpec;
import me.majhrs16.cht.core.template.FormatSpec;
import me.majhrs16.cht.core.template.Template;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the plugin's two YAML files into typed configuration objects.
 *
 * <p>The formats file uses the new template syntax (MiniMessage plus
 * {@code <tr>} spans) instead of the legacy {@code from}/{@code to} blocks:
 * one flat group per {@link ChatMessageType}.</p>
 *
 * <pre>{@code
 * chat:
 *   message: '<gray><tr>%content%</tr></gray>'
 *   tooltip: '<aqua>%lang_source% -> %lang_target%</aqua>'
 *   sound: 'entity.experience_orb.pickup;1.0;1.0'
 * }</pre>
 */
public final class ConfigLoader {

    private static final String[] FORMAT_KEYS = {
        "chat", "private-chat", "mention", "join", "leave",
        "death", "advancement", "sign", "internal",
    };

    private ConfigLoader() {
    }

    /**
     * Parses {@code formats.yml}.
     *
     * @param input file content; closed by the caller.
     * @return a catalog populated with every present group.
     * @throws IOException when the file cannot be parsed.
     */
    @SuppressWarnings("unchecked")
    public static FormatCatalog loadFormats(InputStream input) throws IOException {
        Map<String, Object> root = loadMap(input);
        FormatCatalog catalog = new FormatCatalog();

        ChatMessageType[] types = ChatMessageType.values();
        for (int i = 0; i < types.length; i++) {
            Object group = root.get(FORMAT_KEYS[i]);
            if (!(group instanceof Map)) {
                continue;
            }
            Map<String, Object> fields = (Map<String, Object>) group;
            String message = asString(fields.get("message"));
            if (message == null || message.trim().isEmpty()) {
                continue;
            }
            FormatSpec.Builder builder = FormatSpec.builder(Template.of(message));

            String tooltip = asString(fields.get("tooltip"));
            if (tooltip != null) {
                builder.tooltip(Template.of(tooltip));
            }
            String sound = asString(fields.get("sound"));
            if (sound != null) {
                builder.sound(parseSound(sound));
            }
            catalog.register(types[i], builder.build());
        }
        return catalog;
    }

    /**
     * Parses {@code config.yml}.
     *
     * @param input file content; closed by the caller.
     * @return typed settings, never null.
     */
    @SuppressWarnings("unchecked")
    public static ChatSettings loadSettings(InputStream input) throws IOException {
        Map<String, Object> root = loadMap(input);

        ChatSettings.Builder builder = ChatSettings.builder();
        builder.defaultLanguage(Language.of(asString(root.get("default-language")))
            .orElse(Language.EN));

        builder.translateToSender(bool(root.get("translate-to-sender"), false));
        builder.translateSigns(bool(root.get("translate-signs"), true));
        builder.connectionLostMarker(bool(root.get("connection-lost-marker"), true));
        builder.debug(bool(root.get("debug"), false));

        Object antiSpam = root.get("anti-spam");
        if (antiSpam instanceof Map) {
            Map<String, Object> spam = (Map<String, Object>) antiSpam;
            builder.antiSpamEnabled(bool(spam.get("enabled"), false));
            builder.antiSpamLimitPerTick(
                intOf(spam.get("limit-per-tick"), 5));
        }

        Object translator = root.get("translator");
        if (translator instanceof Map) {
            Map<String, Object> provider = (Map<String, Object>) translator;
            builder.translatorProvider(stringOf(provider.get("provider"), "google"));
            builder.libreUrl(stringOf(provider.get("libre-url"), "http://localhost:5000"));
            builder.libreKey(stringOf(provider.get("libre-key"), ""));
        }
        return builder.build();
    }

    private static Map<String, Object> loadMap(InputStream input) throws IOException {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            return map;
        }
        throw new IOException("YAML root is not a mapping");
    }

    private static SoundSpec parseSound(String raw) {
        String[] parts = raw.split(";");
        String name = parts[0].trim();
        float volume = parts.length > 1 ? parseFloat(parts[1]) : 1f;
        float pitch = parts.length > 2 ? parseFloat(parts[2]) : 1f;
        return new SoundSpec(name, volume, pitch);
    }

    private static float parseFloat(String raw) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(asString(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = asString(value);
        if (text == null) {
            return fallback;
        }
        return Boolean.parseBoolean(text.toLowerCase(Locale.ROOT));
    }

    private static String stringOf(Object value, String fallback) {
        String text = asString(value);
        return text == null ? fallback : text;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
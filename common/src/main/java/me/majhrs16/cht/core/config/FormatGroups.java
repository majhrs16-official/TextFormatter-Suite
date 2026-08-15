package me.majhrs16.cht.core.config;

import me.majhrs16.cht.core.message.SoundSpec;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of format groups declared in {@code formats.yml}.
 *
 * <p>A group lives on an arbitrary dotted path (e.g. {@code remitente_user},
 * {@code destinatario_owner}, {@code to_console}, {@code from_discord}) and may
 * declare:</p>
 *
 * <pre>{@code
 * to_owner:
 *   messages:
 *     formats:
 *       - '&f<&b%player_name%&f> &a$ct_messages$'
 *     texts:
 *       - 'ey!'
 *   toolTips:
 *     formats:
 *       - '&f[&6%ct_lang_source%&f] &a%ct_messages%'
 *   sounds:
 *     entity.experience_orb.pickup:          # name
 *       volume: 1.0
 *       pitch: 1.0
 *   sourceLang: [es]
 *   targetLang: [en]
 * }</pre>
 *
 * <p>Groups are resolved by path at format time with the legacy fallbacks:
 * explicit {@code messages.formats/texts}, shared {@code texts} below the
 * group, {@code sourceLang}/{@code targetLang} overrides, and sounds declared
 * as a section keyed by sound name.</p>
 */
public final class FormatGroups {

    private final Map<String, Object> root;
    private final Map<String, Object> flattened;

    private FormatGroups(Map<String, Object> root) {
        this.root = Collections.unmodifiableMap(new LinkedHashMap<>(root));
        this.flattened = flatten(root);
    }

    @SuppressWarnings("unchecked")
    public static FormatGroups load(InputStream input) throws IOException {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        if (parsed instanceof Map) {
            return new FormatGroups((Map<String, Object>) parsed);
        }
        return new FormatGroups(Collections.<String, Object>emptyMap());
    }

    /** @return true when the group exists on the given dotted path. */
    public boolean has(String path) {
        return flattened.containsKey(path) || resolveMap(path) != null;
    }

    /** Raw formats declared at {@code path.messages.formats} (or nested fallbacks). */
    public String[] messageFormats(String path) {
        return strings(resolve(path + ".messages.formats"));
    }

    /** Raw texts declared at {@code path.messages.texts} or {@code path.texts}. */
    public String[] messageTexts(String path) {
        String[] explicit = strings(resolve(path + ".messages.texts"));
        if (explicit.length == 0) {
            explicit = strings(resolve(path + ".texts"));
        }
        return explicit;
    }

    public String[] toolTipFormats(String path) {
        return strings(resolve(path + ".toolTips.formats"));
    }

    public String[] toolTipTexts(String path) {
        String[] explicit = strings(resolve(path + ".toolTips.texts"));
        if (explicit.length == 0) {
            explicit = strings(resolve(path + ".texts"));
        }
        return explicit;
    }

    public String[] sourceLang(String path) {
        return strings(resolve(path + ".sourceLang"));
    }

    public String[] targetLang(String path) {
        return strings(resolve(path + ".targetLang"));
    }

    @SuppressWarnings("unchecked")
    public SoundSpec[] sounds(String path) {
        Object node = resolve(path + ".sounds");
        if (!(node instanceof Map)) {
            return new SoundSpec[0];
        }
        Map<String, Object> section = (Map<String, Object>) node;
        List<SoundSpec> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : section.entrySet()) {
            Map<String, Object> attrs = entry.getValue() instanceof Map
                ? (Map<String, Object>) entry.getValue() : Collections.<String, Object>emptyMap();
            float volume = floatOf(attrs.get("volume"), 1f);
            float pitch = floatOf(attrs.get("pitch"), 1f);
            result.add(new SoundSpec(entry.getKey(), volume, pitch));
        }
        return result.toArray(new SoundSpec[0]);
    }

    // -- internal resolution -------------------------------------------------

    private Object resolve(String dotted) {
        Object current = root;
        for (String part : dotted.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(part);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveMap(String dotted) {
        Object value = resolve(dotted);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static Map<String, Object> flatten(Map<String, Object> root) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flattenInto(root, "", flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(Map<String, Object> node, String prefix, Map<String, Object> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                flattenInto((Map<String, Object>) entry.getValue(), key, out);
            } else {
                out.put(key, entry.getValue());
            }
        }
    }

    private static String[] strings(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            String[] result = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                result[i] = String.valueOf(list.get(i));
            }
            return result;
        }
        if (value instanceof String) {
            return new String[] { (String) value };
        }
        if (value == null) {
            return new String[0];
        }
        return new String[] { String.valueOf(value) };
    }

    private static float floatOf(Object value, float fallback) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        try {
            return value == null ? fallback : Float.parseFloat(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
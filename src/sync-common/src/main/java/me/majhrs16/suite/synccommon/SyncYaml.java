package me.majhrs16.suite.synccommon;

import me.majhrs16.suite.api.config.ConfigVersion;
import me.majhrs16.suite.api.config.ConfigVersionException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lectura tolerante de YAML para la configuración de los submódulos sync.
 *
 * <p>Un archivo ausente, ilegible, vacío o cuya raíz no sea un mapa devuelve un
 * mapa vacío en lugar de lanzar. Los submódulos aplican sus propios defaults y
 * validan la semántica (token, ids, puertos) antes de crear el sink.</p>
 */
public final class SyncYaml {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private SyncYaml() {
    }

    /**
     * @param file archivo YAML
     * @return mapa raíz, o vacío si no existe / no se puede leer / no es un mapa
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Object root = YAML.load(Files.readString(file));
            if (!(root instanceof Map<?, ?> map)) {
                return Map.of();
            }
            ConfigVersion.require(map, file);
            return (Map<String, Object>) map;
        } catch (ConfigVersionException exception) {
            throw exception;
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        }
    }

    public static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    public static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static double doubleValue(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static List<String> stringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                values.add(String.valueOf(item).trim());
            }
        }
        return List.copyOf(values);
    }

    /**
     * Parses an ISO-8601 duration ({@code PT5S}, {@code PT5M}) or a plain
     * millisecond number.
     */
    public static Duration duration(Map<String, Object> map, String key, Duration fallback) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return Duration.ofMillis(n.longValue());
        }
        if (value instanceof String s) {
            String text = s.trim();
            try {
                return Duration.parse(text);
            } catch (RuntimeException ignored) {
                try {
                    return Duration.ofMillis(Long.parseLong(text));
                } catch (NumberFormatException alsoIgnored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}

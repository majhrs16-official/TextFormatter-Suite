package me.majhrs16.suite.chattranslator;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Carga la configuración específica de {@code chattranslator/config.yml}.
 * <p>
 * Este archivo es independiente de {@code config.yml} (global) y
 * {@code translators/*.yml} (backends individuales).
 * </p>
 */
public final class ChatTranslatorConfig {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private ChatTranslatorConfig() {
    }

    /**
     * Carga la configuración desde {@code dir/config/chattranslator/config.yml}.
     * <p>
     * Se mantiene la ruta heredada {@code chattranslator/config.yml} durante la
     * migración al layout de {@code storage.md}.
     * </p>
     *
     * @param dir directorio base del suite (ej. plugin data folder)
     * @return configuración cargada, o defaults si no existe
     */
    public static Config load(Path dir) {
        return loadFile(resolveConfigFile(dir));
    }

    /**
     * Loads {@code <configDir>/config.yml} for a module-scoped configuration
     * directory.
     *
     * @param configDir module config directory
     * @return configuration, or defaults if the file is missing or invalid
     */
    static Config loadFromConfigDir(Path configDir) {
        return loadFile(configDir.resolve("config.yml"));
    }

    private static Config loadFile(Path configFile) {
        if (!Files.exists(configFile)) {
            return Config.defaults();
        }
        try {
            Object root = YAML.load(Files.readString(configFile));
            if (!(root instanceof Map)) {
                return Config.defaults();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;

            String defaultBackend = str(map.get("default-backend"), null);
            boolean contextOverrideEnabled = bool(map.get("context-override-enabled"), true);
            int cacheMaxSize = intOf(map.get("cache-max-size"), 10_000);
            int detectionCacheMaxSize = intOf(map.get("detection-cache-max-size"), 5_000);
            long translationTimeoutMs = longOf(map.get("translation-timeout-ms"), 30_000L);

            return new Config(defaultBackend, contextOverrideEnabled, cacheMaxSize,
                detectionCacheMaxSize, translationTimeoutMs);
        } catch (IOException | RuntimeException e) {
            // Config inválido → usar defaults
            return Config.defaults();
        }
    }

    /** @return la ruta canónica si existe, o la heredada; la canónica si no hay ninguna. */
    public static Path resolveConfigFile(Path dir) {
        Path canonical = dir.resolve("config").resolve("chattranslator").resolve("config.yml");
        if (Files.exists(canonical)) {
            return canonical;
        }
        Path intermediate = dir.resolve("chattranslator").resolve("config.yml");
        return Files.exists(intermediate) ? intermediate : canonical;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static long longOf(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    /**
     * Configuración inmutable del ChatTranslator.
     *
     * @param defaultBackend           backend predeterminado (ej. "google", "libre"), null = auto
     * @param contextOverrideEnabled   si se permite override por contexto en runtime
     * @param cacheMaxSize             tamaño máximo de caché de traducciones
     * @param detectionCacheMaxSize    tamaño máximo de caché de detección
     * @param translationTimeoutMs     timeout para peticiones de traducción
     */
    public record Config(
            String defaultBackend,
            boolean contextOverrideEnabled,
            int cacheMaxSize,
            int detectionCacheMaxSize,
            long translationTimeoutMs
    ) {
        public static Config defaults() {
            return new Config("google", true, 10000, 5000, 30_000);
        }
    }
}
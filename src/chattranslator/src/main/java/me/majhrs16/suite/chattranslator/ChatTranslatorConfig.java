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
     * Carga la configuración desde {@code dir/chattranslator/config.yml}.
     *
     * @param dir directorio base del suite (ej. plugin data folder)
     * @return configuración cargada, o defaults si no existe
     */
    public static Config load(Path dir) {
        Path configFile = dir.resolve("chattranslator").resolve("config.yml");
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
            int cacheMaxSize = (int) (long) map.getOrDefault("cache-max-size", 10000);
            int detectionCacheMaxSize = (int) (long) map.getOrDefault("detection-cache-max-size", 5000);
            long translationTimeoutMs = (long) map.getOrDefault("translation-timeout-ms", 30_000);

            return new Config(defaultBackend, contextOverrideEnabled, cacheMaxSize,
                detectionCacheMaxSize, translationTimeoutMs);
        } catch (IOException | RuntimeException e) {
            // Config inválido → usar defaults
            return Config.defaults();
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
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
            return new Config(null, true, 10000, 5000, 30_000);
        }
    }
}
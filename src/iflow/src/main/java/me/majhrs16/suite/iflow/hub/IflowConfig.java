package me.majhrs16.suite.iflow.hub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import me.majhrs16.suite.api.spi.PluginLogger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Configuración del hub iFlow.
 *
 * <p>Se lee de {@code <configDir>/config.yml}; en producción
 * {@code config/iflow/config.yml}. El hub no importa {@code HostPaths}
 * porque es un módulo neutral: recibe el directorio ya resuelto por el
 * runtime.</p>
 */
public final class IflowConfig {

    public static final String DEFAULT_BACKEND = "iflow-firewall";

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private final String engine;
    private final boolean fallback;

    public IflowConfig(String engine, boolean fallback) {
        this.engine = normalize(engine);
        this.fallback = fallback;
    }

    public static IflowConfig defaults() {
        return new IflowConfig(DEFAULT_BACKEND, true);
    }

    /**
     * Carga {@code <configDir>/config.yml}. Un archivo ausente o inválido
     * produce los valores por defecto; el hub nunca deja de arrancar por una
     * configuración incompleta.
     */
    public static IflowConfig load(Path configDir, PluginLogger logger) {
        Objects.requireNonNull(configDir, "configDir");
        Path file = configDir.resolve("config.yml");
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        try {
            Object root = YAML.load(Files.readString(file));
            if (!(root instanceof Map<?, ?> map)) {
                warn(logger, "iFlow config is not a map; using defaults");
                return defaults();
            }
            String engine = string(map.get("engine"));
            if (engine == null || engine.isBlank()) {
                engine = string(map.get("default-backend"));
            }
            boolean fallback = bool(map.get("fallback-on-unavailable"),
                bool(map.get("fallback"), true));
            return new IflowConfig(engine, fallback);
        } catch (IOException | RuntimeException e) {
            warn(logger, "Cannot read iFlow config " + file + ": " + e.getMessage());
            return defaults();
        }
    }

    public String engine() {
        return engine;
    }

    public boolean fallback() {
        return fallback;
    }

    /**
     * Acepta tanto el nombre canónico del paquete como el alias corto usado en
     * la configuración histórica.
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_BACKEND;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return switch (trimmed) {
            case "firewall", "legacy", "iflow-firewall" -> "iflow-firewall";
            case "vm", "iflow-vm" -> "iflow-vm";
            case "wasm", "iflow-wasm" -> "iflow-wasm";
            default -> trimmed;
        };
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> fallback;
        };
    }

    private static void warn(PluginLogger logger, String message) {
        if (logger != null) {
            logger.warn(message);
        }
    }
}

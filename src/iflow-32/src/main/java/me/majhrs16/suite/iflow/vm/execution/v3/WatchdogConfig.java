package me.majhrs16.suite.iflow.vm.execution.v3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import me.majhrs16.suite.api.config.ConfigVersion;
import me.majhrs16.suite.api.config.ConfigVersionException;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Configuración del watchdog de paso de {@code iflow-32}.
 *
 * <p>El watchdog está activo por defecto. El operador puede desactivarlo en
 * {@code config/iflow-32/config.yml} para hosts muy lentos donde una sola instrucción puede
 * superar 50 ms por causas legítimas (CPU antigua, JIT frío o pausas del host). Desactivarlo es
 * una decisión explícita: no cambia el quantum de preempción, que sigue siendo no enmascarable, y
 * deja sin recuperación un {@code cpu.step()} que no retorne.</p>
 *
 * @param enabled si el watchdog de paso debe dispararse
 */
public record WatchdogConfig(boolean enabled) {

    public static final boolean DEFAULT_ENABLED = true;

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    public static WatchdogConfig defaults() {
        return new WatchdogConfig(DEFAULT_ENABLED);
    }

    /**
     * Carga {@code <configDir>/config.yml}. Un archivo ausente usa el valor por defecto; un archivo
     * presente pero inválido falla de forma explícita para no desactivar el watchdog por accidente.
     *
     * @param configDir directorio de configuración del módulo ({@code config/iflow-32})
     * @param logger    logger opcional para avisar de un watchdog desactivado
     */
    public static WatchdogConfig load(Path configDir, PluginLogger logger) {
        Objects.requireNonNull(configDir, "configDir");
        return loadFile(resolveConfigFile(configDir), logger);
    }

    /** @return ruta canónica {@code <configDir>/config.yml}. */
    public static Path resolveConfigFile(Path configDir) {
        return configDir.resolve("config.yml");
    }

    static WatchdogConfig loadFile(Path file, PluginLogger logger) {
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        try {
            Object root = YAML.load(Files.readString(file));
            if (!(root instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                        "iflow-32 config must be a map: " + file);
            }
            ConfigVersion.require(map, file);

            Object rawWatchdog = map.get("watchdog");
            if (rawWatchdog == null) {
                return defaults();
            }
            if (!(rawWatchdog instanceof Map<?, ?> watchdog)) {
                throw new IllegalArgumentException(
                        "watchdog must be a map with 'enabled': " + file);
            }
            boolean enabled = bool(watchdog.get("enabled"), file);
            if (!enabled && logger != null) {
                logger.warn("iflow-32: watchdog disabled by " + file
                        + "; a guest step that never returns cannot be recovered");
            }
            return new WatchdogConfig(enabled);
        } catch (ConfigVersionException | IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot read iflow-32 config " + file + ": " + e.getMessage(), e);
        }
    }

    private static boolean bool(Object value, Path file) {
        if (value == null) {
            return DEFAULT_ENABLED;
        }
        if (value instanceof Boolean enabled) {
            return enabled;
        }
        throw new IllegalArgumentException(
                "watchdog.enabled must be a boolean in " + file + ": " + value);
    }
}

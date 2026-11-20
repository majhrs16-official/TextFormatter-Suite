package me.majhrs16.suite.iflow.vm.execution.v3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import me.majhrs16.suite.api.config.ConfigVersion;
import me.majhrs16.suite.api.config.ConfigVersionException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Configuración de {@code CORE_COUNT} de {@code iflow-32}.
 *
 * <p>El valor canónico vive en {@code config/iflow-32/config.yml} bajo la clave
 * {@code core-count}. Un archivo ausente o una clave ausente usan un único core. El rango
 * arquitectónico es 1..64, pero V3-2 todavía no implementa el multinúcleo: un valor mayor que 1
 * falla de forma explícita en lugar de degradarse a un solo core.</p>
 *
 * @param coreCount número de vCPU de la misma VM
 */
public record CoreCountConfig(int coreCount) {

    public static final String KEY = "core-count";
    public static final int MIN_CORE_COUNT = 1;
    public static final int MAX_CORE_COUNT = 64;

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    public CoreCountConfig {
        if (coreCount < MIN_CORE_COUNT || coreCount > MAX_CORE_COUNT) {
            throw new IllegalArgumentException(
                    "core-count must be " + MIN_CORE_COUNT + ".." + MAX_CORE_COUNT
                            + ": " + coreCount);
        }
    }

    public static CoreCountConfig defaults() {
        return new CoreCountConfig(MIN_CORE_COUNT);
    }

    /**
     * Carga {@code <configDir>/config.yml}. Un archivo ausente usa un único core; un archivo
     * presente pero inválido falla de forma explícita.
     *
     * @param configDir directorio de configuración del módulo ({@code config/iflow-32})
     */
    public static CoreCountConfig load(Path configDir) {
        Objects.requireNonNull(configDir, "configDir");
        return loadFile(configDir.resolve("config.yml"));
    }

    static CoreCountConfig loadFile(Path file) {
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

            Object rawCoreCount = map.get(KEY);
            if (rawCoreCount == null) {
                return defaults();
            }
            if (!(rawCoreCount instanceof Number number)) {
                throw new IllegalArgumentException(
                        "core-count must be an integer in " + file + ": " + rawCoreCount);
            }
            long parsed = number.longValue();
            if (number.doubleValue() != parsed) {
                throw new IllegalArgumentException(
                        "core-count must be an integer in " + file + ": " + rawCoreCount);
            }
            if (parsed < MIN_CORE_COUNT || parsed > MAX_CORE_COUNT) {
                throw new IllegalArgumentException(
                        "core-count must be " + MIN_CORE_COUNT + ".." + MAX_CORE_COUNT
                                + ": " + rawCoreCount);
            }
            CoreCountConfig config = new CoreCountConfig((int) parsed);
            config.requireSupported();
            return config;
        } catch (ConfigVersionException | IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot read iflow-32 config " + file + ": " + e.getMessage(), e);
        }
    }

    /** @return {@code true} si la configuración pide más de un core. */
    public boolean multicore() {
        return coreCount > 1;
    }

    /**
     * Falla explícitamente mientras V3-2 no entregue estado por core, atómicas, IPI, PIC por core,
     * TLB shootdown y journal concurrente.
     */
    public void requireSupported() {
        if (multicore()) {
            throw new UnsupportedOperationException(
                    "CORE_COUNT > 1 requires V3-2; refusing to degrade to a single core");
        }
    }
}

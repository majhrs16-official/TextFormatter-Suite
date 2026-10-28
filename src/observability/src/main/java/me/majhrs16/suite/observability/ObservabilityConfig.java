package me.majhrs16.suite.observability;

import me.majhrs16.suite.synccommon.SyncYaml;

import java.nio.file.Path;
import java.util.Map;

/**
 * Configuración del paquete {@code observability}.
 *
 * @param enabled     si el endpoint de métricas debe arrancar
 * @param metricsPort puerto Prometheus ({@code 0} = efímero)
 * @param metricsBind dirección de escucha
 */
public record ObservabilityConfig(boolean enabled, int metricsPort, String metricsBind) {

    public static final int DEFAULT_PORT = 9090;
    public static final String DEFAULT_BIND = "127.0.0.1";

    public ObservabilityConfig {
        metricsBind = metricsBind == null || metricsBind.isBlank() ? DEFAULT_BIND : metricsBind.trim();
    }

    /**
     * @param configDir carpeta privada del módulo ({@code config/observability})
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static ObservabilityConfig loadFromConfigDir(Path configDir) {
        Map<String, Object> map = SyncYaml.readMap(configDir.resolve("config.yml"));
        return new ObservabilityConfig(
            SyncYaml.bool(map, "enabled", true),
            SyncYaml.integer(map, "metricsPort", DEFAULT_PORT),
            SyncYaml.string(map, "metricsBind", DEFAULT_BIND)
        );
    }

    /** @return {@code true} si el endpoint puede arrancar con esta configuración. */
    public boolean usable() {
        return enabled && metricsPort >= 0 && metricsPort <= 65535;
    }
}

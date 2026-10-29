package me.majhrs16.suite.syncvelocity;

import me.majhrs16.suite.api.velocity.VelocitySettings;
import me.majhrs16.suite.synccommon.SyncConfigPaths;
import me.majhrs16.suite.synccommon.SyncYaml;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Configuration of the neutral Velocity sink.
 *
 * <p>This class has no Velocity API types. The adapter only supplies the
 * transport; retry, mapping, queue and metrics policy live here.</p>
 */
public final class VelocityConfig {

    public static final String DEFAULT_MAPPING = "* -> chat.hub";

    public boolean enabled = true;
    public String transport = "velocity";
    public String channel = VelocitySettings.DEFAULT_CHANNEL;
    public String subchannel = VelocitySettings.DEFAULT_SUBCHANNEL;
    public String secret = "";
    public List<String> servers = List.of();
    public String mapping = DEFAULT_MAPPING;
    public Duration retryInitialDelay = Duration.ofSeconds(5);
    public Duration retryMaxDelay = Duration.ofMinutes(5);
    public double retryMultiplier = 2.0;
    public int maxRetries = 10;
    public int maxQueueSize = 10000;
    public Duration queueDrainTimeout = Duration.ofSeconds(30);
    public boolean dynamicDiscovery = true;
    public Duration metricsInterval = Duration.ofMinutes(1);

    /**
     * @param dataFolder carpeta de datos de la suite
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static VelocityConfig load(Path dataFolder) {
        return loadFile(SyncConfigPaths.velocity(dataFolder));
    }

    /**
     * @param configDir carpeta privada del módulo ({@code config/sync-velocity-core})
     * @return configuración con defaults si el archivo no existe o no es válido
     */
    public static VelocityConfig loadFromConfigDir(Path configDir) {
        return loadFile(configDir.resolve("config.yml"));
    }

    private static VelocityConfig loadFile(Path file) {
        Map<String, Object> map = SyncYaml.readMap(file);
        VelocityConfig config = new VelocityConfig();
        config.enabled = SyncYaml.bool(map, "enabled", true);
        config.transport = SyncYaml.string(map, "transport", config.transport);
        config.channel = SyncYaml.string(map, "channel", config.channel);
        config.subchannel = SyncYaml.string(map, "subchannel", config.subchannel);
        config.secret = SyncYaml.string(map, "secret", "");
        config.servers = SyncYaml.stringList(map, "servers");
        config.mapping = SyncYaml.string(map, "mapping", DEFAULT_MAPPING);
        config.retryInitialDelay = SyncYaml.duration(
            map, "retry-initial-delay", config.retryInitialDelay);
        config.retryMaxDelay = SyncYaml.duration(
            map, "retry-max-delay", config.retryMaxDelay);
        config.retryMultiplier = SyncYaml.doubleValue(
            map, "retry-multiplier", config.retryMultiplier);
        config.maxRetries = SyncYaml.integer(map, "max-retries", config.maxRetries);
        config.maxQueueSize = SyncYaml.integer(map, "max-queue-size", config.maxQueueSize);
        config.queueDrainTimeout = SyncYaml.duration(
            map, "queue-drain-timeout", config.queueDrainTimeout);
        config.dynamicDiscovery = SyncYaml.bool(
            map, "dynamic-discovery", config.dynamicDiscovery);
        config.metricsInterval = SyncYaml.duration(
            map, "metrics-interval", config.metricsInterval);
        return config;
    }

    public VelocitySettings settings() {
        return new VelocitySettings(channel, subchannel);
    }

    /** @throws IllegalArgumentException if a policy value is invalid. */
    public void validate() {
        if (retryInitialDelay.isNegative() || retryInitialDelay.isZero()) {
            throw new IllegalArgumentException("retryInitialDelay must be positive");
        }
        if (retryMaxDelay.isNegative() || retryMaxDelay.isZero()) {
            throw new IllegalArgumentException("retryMaxDelay must be positive");
        }
        if (retryMultiplier <= 1.0) {
            throw new IllegalArgumentException("retryMultiplier must be > 1.0");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (maxQueueSize <= 0) {
            throw new IllegalArgumentException("maxQueueSize must be > 0");
        }
    }
}

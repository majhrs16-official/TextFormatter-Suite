package me.majhrs16.suite.syncvelocity;

import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import java.util.List;
import java.util.Optional;

import com.velocitypowered.api.proxy.ProxyServer;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * Velocity plugin entry point for TextFormatter Suite.
 * Bootstraps the VelocitySink and registers it with the suite's host.
 */
@Plugin(
    id = "textformatter-suite-velocity",
    name = "TextFormatter Suite (Velocity)",
    version = "@version@",
    description = "Velocity proxy sync module for TextFormatter Suite",
    authors = {"majhrs16"}
)
public final class VelocityPlugin implements Module {

    private static final Logger LOGGER = LoggerFactory.getLogger(VelocityPlugin.class);

    private final ProxyServer proxy;
    private final Path dataDirectory;
    private VelocitySink sink;

    @Inject
    public VelocityPlugin(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-velocity")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
            .build();
    }

    /**
     * Called by Velocity after plugin loading.
     */
    public void onLoad() {
        LOGGER.info("Loading TextFormatter Suite Velocity sync module...");

        // Load config
        Path configFile = dataDirectory.resolve("sync/velocity.yml");
        VelocitySink.Config config = new VelocitySink.Config();

        if (Files.exists(configFile)) {
            try {
                Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
                String content = Files.readString(configFile);
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) yaml.load(content);
                if (map != null) {
                    // Parse config with validation
                    config.enabled = Optional.ofNullable(map.get("enabled")).map(o -> Boolean.parseBoolean(String.valueOf(o))).orElse(true);
                    config.secret = Optional.ofNullable(map.get("secret")).map(o -> String.valueOf(o)).orElse("");
                    
                    Object sv = map.get("servers");
                    if (sv instanceof List) {
                        config.servers = ((List<?>) sv).stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .toList();
                    }
                    
                    config.mapping = Optional.ofNullable(map.get("mapping")).map(o -> String.valueOf(o)).orElse("* -> chat.hub");
                    
                    // Retry config
                    config.retryInitialDelay = parseDuration(map.get("retry-initial-delay"), Duration.ofSeconds(5));
                    config.retryMaxDelay = parseDuration(map.get("retry-max-delay"), Duration.ofMinutes(5));
                    config.retryMultiplier = Optional.ofNullable(map.get("retry-multiplier")).map(o -> Double.parseDouble(String.valueOf(o))).orElse(2.0);
                    config.maxRetries = Optional.ofNullable(map.get("max-retries")).map(o -> Integer.parseInt(String.valueOf(o))).orElse(10);
                    config.maxQueueSize = Optional.ofNullable(map.get("max-queue-size")).map(o -> Integer.parseInt(String.valueOf(o))).orElse(10000);
                    config.queueDrainTimeout = parseDuration(map.get("queue-drain-timeout"), Duration.ofSeconds(30));
                    config.dynamicDiscovery = Optional.ofNullable(map.get("dynamic-discovery")).map(o -> Boolean.parseBoolean(String.valueOf(o))).orElse(true);
                    config.metricsInterval = parseDuration(map.get("metrics-interval"), Duration.ofMinutes(1));
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load velocity.yml config: {}", e.getMessage());
            }
        }

        try {
            config.validate();
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid Velocity config: {}", e.getMessage());
            return;
        }

        // Create and start sink
        sink = new VelocitySink(proxy, config);
        sink.start();
        LOGGER.info("VelocitySink started successfully (enabled={})", config.enabled);
    }

    private Duration parseDuration(Object value, Duration defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Duration) return (Duration) value;
        if (value instanceof String) {
            try {
                return Duration.parse((String) value);
            } catch (Exception e) {
                LOGGER.warn("Invalid duration format '{}', using default {}", value, defaultValue);
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public void onDisable() {
        if (sink != null) {
            sink.stop();
            LOGGER.info("VelocitySink stopped");
        }
    }
}
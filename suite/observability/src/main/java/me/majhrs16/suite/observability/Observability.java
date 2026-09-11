package me.majhrs16.suite.observability;

import me.majhrs16.suite.observability.endpoint.DebugEndpoint;
import me.majhrs16.suite.observability.endpoint.MetricsEndpoint;
import me.majhrs16.suite.observability.health.HealthCheckRegistry;
import me.majhrs16.suite.observability.metrics.MetricsCollector;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main entry point for the Observability module.
 * Coordinates metrics, debug endpoints, and health checks.
 */
public final class Observability {

    private final MetricsEndpoint metricsEndpoint;
    private final DebugEndpoint debugEndpoint;
    private final HealthCheckRegistry healthCheckRegistry;
    private final PluginLogger logger;
    private volatile boolean started = false;

    private Observability(SuiteHost host, MessageDispatcher dispatcher,
                          ChannelRegistry channels, PluginLogger logger,
                          int metricsPort, int debugPort) throws IOException {
        this.logger = logger;
        this.metricsEndpoint = new MetricsEndpoint(logger, metricsPort, "/metrics");
        this.debugEndpoint = new DebugEndpoint(host, dispatcher, channels, logger);
        this.healthCheckRegistry = new HealthCheckRegistry();

        // Register default health checks
        registerDefaultChecks();
    }

    /**
     * Creates and starts the observability module.
     */
    public static Observability create(SuiteHost host, MessageDispatcher dispatcher,
                                       ChannelRegistry channels, PluginLogger logger,
                                       int metricsPort, int debugPort) throws IOException {
        Observability obs = new Observability(host, dispatcher, channels, logger, metricsPort, debugPort);
        obs.start();
        return obs;
    }

    /**
     * Creates with default ports (9090 metrics, 9091 debug).
     */
    public static Observability createDefault(SuiteHost host, MessageDispatcher dispatcher,
                                               ChannelRegistry channels, PluginLogger logger) throws IOException {
        return create(host, dispatcher, channels, logger, 9090, 9091);
    }

    private void registerDefaultChecks() {
        // JVM health check
        healthCheckRegistry.register("jvm", () -> {
            long freeMemory = Runtime.getRuntime().freeMemory();
            long totalMemory = Runtime.getRuntime().totalMemory();
            long maxMemory = Runtime.getRuntime().maxMemory();
            double usedPercent = (double) (totalMemory - freeMemory) / maxMemory * 100;

            if (usedPercent > 90) {
                return HealthCheckRegistry.HealthCheckResult.unhealthy(
                    "JVM heap usage critical: " + String.format("%.1f%%", usedPercent),
                    Map.of("used_percent", usedPercent, "max_mb", maxMemory / 1024 / 1024)
                );
            } else if (usedPercent > 75) {
                return HealthCheckRegistry.HealthCheckResult.degraded(
                    "JVM heap usage high: " + String.format("%.1f%%", usedPercent),
                    Map.of("used_percent", usedPercent, "max_mb", maxMemory / 1024 / 1024)
                );
            }
            return HealthCheckRegistry.HealthCheckResult.healthy(
                "JVM healthy",
                Map.of("used_percent", usedPercent, "max_mb", maxMemory / 1024 / 1024)
            );
        });

        // Thread count check
        healthCheckRegistry.register("threads", () -> {
            int threadCount = Thread.activeCount();
            if (threadCount > 500) {
                return HealthCheckRegistry.HealthCheckResult.degraded(
                    "High thread count: " + threadCount,
                    Map.of("count", threadCount)
                );
            }
            return HealthCheckRegistry.HealthCheckResult.healthy(
                "Thread count normal",
                Map.of("count", threadCount)
            );
        });
    }

    /**
     * Starts all observability components.
     */
    public void start() {
        if (started) return;
        metricsEndpoint.start();
        debugEndpoint.start();
        started = true;
        logger.info("Observability module started (metrics:" + metricsEndpoint.getPort() + ", debug:" + debugEndpoint.getPort() + ")");
    }

    /**
     * Stops all observability components.
     */
    public void stop() {
        if (!started) return;
        metricsEndpoint.stop();
        debugEndpoint.stop();
        started = false;
        logger.info("Observability module stopped");
    }

    public boolean isStarted() {
        return started;
    }

    public MetricsEndpoint getMetricsEndpoint() {
        return metricsEndpoint;
    }

    public DebugEndpoint getDebugEndpoint() {
        return debugEndpoint;
    }

    public HealthCheckRegistry getHealthCheckRegistry() {
        return healthCheckRegistry;
    }

    public MetricsCollector getMetricsCollector() {
        return new MetricsCollector(); // static methods
    }

    /**
     * Gets a summary of current health status.
     */
    public Map<String, Object> getHealthSummary() {
        var results = healthCheckRegistry.runAll();
        var overall = healthCheckRegistry.getOverallStatus();

        return Map.of(
            "status", overall.name(),
            "timestamp", Instant.now().toString(),
            "checks", results.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> Map.of(
                        "status", e.getValue().status().name(),
                        "message", e.getValue().message(),
                        "durationMs", e.getValue().durationMs(),
                        "details", e.getValue().details()
                    )
                ))
        );
    }

    // Getters for port info (need to add to endpoints)
    private int getMetricsPort() {
        try {
            var field = MetricsEndpoint.class.getDeclaredField("server");
            field.setAccessible(true);
            var server = field.get(metricsEndpoint);
            return (int) server.getClass().getMethod("getAddress").invoke(server).getClass().getMethod("getPort").invoke(server.getAddress());
        } catch (Exception e) {
            return 9090;
        }
    }

    private int getDebugPort() {
        try {
            var field = DebugEndpoint.class.getDeclaredField("server");
            field.setAccessible(true);
            var server = field.get(debugEndpoint);
            return (int) server.getClass().getMethod("getAddress").invoke(server).getClass().getMethod("getPort").invoke(server.getAddress());
        } catch (Exception e) {
            return 9091;
        }
    }
}
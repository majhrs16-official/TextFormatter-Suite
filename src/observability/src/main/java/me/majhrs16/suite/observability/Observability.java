package me.majhrs16.suite.observability;

import me.majhrs16.suite.observability.endpoint.MetricsEndpoint;
import me.majhrs16.suite.observability.health.HealthCheckRegistry;
import me.majhrs16.suite.observability.metrics.MetricsCollector;
import me.majhrs16.suite.api.metrics.Metrics;
import me.majhrs16.suite.api.metrics.MetricsSink;
import me.majhrs16.suite.api.spi.PluginLogger;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Metrics and health facade of the observability package.
 *
 * <p>It owns the Prometheus endpoint and the health registry. It deliberately
 * knows nothing about the host or the debug endpoints: those are host adapters
 * and live in {@code host.debug}.</p>
 */
public final class Observability {

    private final MetricsEndpoint metricsEndpoint;
    private final HealthCheckRegistry healthCheckRegistry;
    private final PluginLogger logger;
    private volatile boolean started = false;
    private volatile MetricsSink metricsSink;

    public Observability(PluginLogger logger, int metricsPort, String metricsBind,
                         HealthCheckRegistry healthCheckRegistry) throws IOException {
        this.logger = logger;
        this.metricsEndpoint = new MetricsEndpoint(logger, metricsPort, "/metrics", metricsBind);
        this.healthCheckRegistry = healthCheckRegistry;
        registerDefaultChecks(healthCheckRegistry);
    }

    /**
     * Creates and starts the metrics facade.
     */
    public static Observability create(PluginLogger logger, int metricsPort, String metricsBind)
            throws IOException {
        Observability observability = new Observability(
                logger, metricsPort, metricsBind, new HealthCheckRegistry());
        observability.start();
        return observability;
    }

    /**
     * Creates with default ports (9090 metrics, localhost bind).
     */
    public static Observability createDefault(PluginLogger logger) throws IOException {
        String metricsBind = System.getProperty("textformattersuite.metrics.bind", "127.0.0.1");
        return create(logger, 9090, metricsBind);
    }

    /**
     * Registers the JVM and thread-count health checks.
     */
    public static void registerDefaultChecks(HealthCheckRegistry registry) {
        registry.register("jvm", () -> {
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

        registry.register("threads", () -> {
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
     * Starts the metrics endpoint and installs the Prometheus metrics sink.
     */
    public void start() {
        if (started) return;
        metricsEndpoint.start();
        MetricsSink sink = MetricsCollector::recordSyncSent;
        Metrics.install(sink);
        metricsSink = sink;
        started = true;
        logger.info("Observability metrics started on port " + metricsEndpoint.getPort());
    }

    /**
     * Stops the metrics endpoint and removes the metrics sink.
     */
    public void stop() {
        if (!started) return;
        Metrics.uninstall(metricsSink);
        metricsSink = null;
        metricsEndpoint.stop();
        started = false;
        logger.info("Observability metrics stopped");
    }

    public boolean isStarted() {
        return started;
    }

    public MetricsEndpoint getMetricsEndpoint() {
        return metricsEndpoint;
    }

    public HealthCheckRegistry getHealthCheckRegistry() {
        return healthCheckRegistry;
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
}

package me.majhrs16.suite.observability.health;

import me.majhrs16.suite.api.spi.SyncSink;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Registry and executor for health checks on sync sinks and other components.
 * Provides health check endpoints and periodic monitoring.
 */
public final class HealthCheckRegistry {

    private final Map<String, HealthCheck> checks = new ConcurrentHashMap<>();
    private final Map<String, HealthCheckResult> lastResults = new ConcurrentHashMap<>();
    private final AtomicLong lastRun = new AtomicLong(0);

    public HealthCheckRegistry() {}

    /**
     * Registers a health check for a component.
     *
     * @param name unique name of the check
     * @param check the health check to execute
     */
    public void register(String name, HealthCheck check) {
        checks.put(name, check);
    }

    /**
     * Registers a health check for a SyncSink.
     */
    public void registerSink(String name, SyncSink sink) {
        register(name, () -> {
            try {
                // Try to send a test message or just verify sink is responsive
                // For now, just check if sink implements a health check method
                if (sink instanceof HealthCheckable) {
                    return ((HealthCheckable) sink).healthCheck();
                }
                // Default: assume healthy if sink exists
                return HealthCheckResult.healthy("Sink registered");
            } catch (Exception e) {
                return HealthCheckResult.unhealthy("Sink check failed: " + e.getMessage());
            }
        });
    }

    /**
     * Runs all registered health checks.
     */
    public Map<String, HealthCheckResult> runAll() {
        Map<String, HealthCheckResult> results = new ConcurrentHashMap<>();
        long start = System.currentTimeMillis();

        for (Map.Entry<String, HealthCheck> entry : checks.entrySet()) {
            String name = entry.getKey();
            HealthCheck check = entry.getValue();

            long checkStart = System.currentTimeMillis();
            HealthCheckResult result;
            try {
                result = check.check();
            } catch (Exception e) {
                result = HealthCheckResult.unhealthy("Check threw exception: " + e.getMessage());
            }
            long durationMs = System.currentTimeMillis() - checkStart;
            result = new HealthCheckResult(
                result.status(),
                result.message(),
                result.details(),
                durationMs
            );
            results.put(name, result);
            lastResults.put(name, result);
        }

        lastRun.set(System.currentTimeMillis());
        return Map.copyOf(results);
    }

    /**
     * Runs a specific health check by name.
     */
    public HealthCheckResult runOne(String name) {
        HealthCheck check = checks.get(name);
        if (check == null) {
            return HealthCheckResult.unhealthy("Check not found: " + name);
        }
        try {
            return check.check();
        } catch (Exception e) {
            return HealthCheckResult.unhealthy("Check threw exception: " + e.getMessage());
        }
    }

    /**
     * Gets the last results without running checks.
     */
    public Map<String, HealthCheckResult> getLastResults() {
        return Map.copyOf(lastResults);
    }

    /**
     * Gets overall system health status.
     */
    public HealthStatus getOverallStatus() {
        if (lastResults.isEmpty()) {
            return HealthStatus.UNKNOWN;
        }
        boolean hasUnhealthy = lastResults.values().stream()
            .anyMatch(r -> r.status() == HealthStatus.UNHEALTHY);
        boolean hasDegraded = lastResults.values().stream()
            .anyMatch(r -> r.status() == HealthStatus.DEGRADED);

        if (hasUnhealthy) return HealthStatus.UNHEALTHY;
        if (hasDegraded) return HealthStatus.DEGRADED;
        return HealthStatus.HEALTHY;
    }

    public long getLastRunTimestamp() {
        return lastRun.get();
    }

    // ============================================================
    // Types
    // ============================================================

    @FunctionalInterface
    public interface HealthCheck {
        HealthCheckResult check();
    }

    public interface HealthCheckable {
        HealthCheckResult healthCheck();
    }

    public enum HealthStatus {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        UNKNOWN
    }

    public record HealthCheckResult(
        HealthStatus status,
        String message,
        Map<String, Object> details,
        long durationMs
    ) {
        public HealthCheckResult(HealthStatus status, String message, Map<String, Object> details) {
            this(status, message, details, 0);
        }

        public static HealthCheckResult healthy(String message) {
            return new HealthCheckResult(HealthStatus.HEALTHY, message, Map.of(), 0);
        }

        public static HealthCheckResult healthy(String message, Map<String, Object> details) {
            return new HealthCheckResult(HealthStatus.HEALTHY, message, details, 0);
        }

        public static HealthCheckResult degraded(String message) {
            return new HealthCheckResult(HealthStatus.DEGRADED, message, Map.of(), 0);
        }

        public static HealthCheckResult degraded(String message, Map<String, Object> details) {
            return new HealthCheckResult(HealthStatus.DEGRADED, message, details, 0);
        }

        public static HealthCheckResult unhealthy(String message) {
            return new HealthCheckResult(HealthStatus.UNHEALTHY, message, Map.of(), 0);
        }

        public static HealthCheckResult unhealthy(String message, Map<String, Object> details) {
            return new HealthCheckResult(HealthStatus.UNHEALTHY, message, details, 0);
        }
    }
}
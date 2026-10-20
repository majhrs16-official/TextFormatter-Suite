package me.majhrs16.suite.observability.health;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthCheckRegistryTest {

    @Test
    void reportsUnknownBeforeAnyCheckRuns() {
        HealthCheckRegistry registry = new HealthCheckRegistry();
        assertEquals(HealthCheckRegistry.HealthStatus.UNKNOWN, registry.getOverallStatus());
        assertEquals(0L, registry.getLastRunTimestamp());
    }

    @Test
    void runsAllChecksAndTracksOverallStatus() {
        HealthCheckRegistry registry = new HealthCheckRegistry();
        registry.register("ok", () -> HealthCheckRegistry.HealthCheckResult.healthy("fine"));
        registry.register("degraded", () -> HealthCheckRegistry.HealthCheckResult.degraded("slow"));
        registry.register("down", () -> HealthCheckRegistry.HealthCheckResult.unhealthy("offline"));

        Map<String, HealthCheckRegistry.HealthCheckResult> results = registry.runAll();

        assertEquals(3, results.size());
        assertEquals(HealthCheckRegistry.HealthStatus.UNHEALTHY, registry.getOverallStatus());
        assertEquals(HealthCheckRegistry.HealthStatus.HEALTHY, results.get("ok").status());
        assertEquals(HealthCheckRegistry.HealthStatus.DEGRADED, results.get("degraded").status());
        assertEquals(HealthCheckRegistry.HealthStatus.UNHEALTHY, results.get("down").status());
        assertTrue(registry.getLastRunTimestamp() > 0);
        assertEquals(3, registry.getLastResults().size());
    }

    @Test
    void convertsThrownChecksToUnhealthyResults() {
        HealthCheckRegistry registry = new HealthCheckRegistry();
        registry.register("boom", () -> {
            throw new IllegalStateException("broken");
        });

        HealthCheckRegistry.HealthCheckResult result = registry.runAll().get("boom");

        assertEquals(HealthCheckRegistry.HealthStatus.UNHEALTHY, result.status());
        assertTrue(result.message().contains("broken"));
    }

    @Test
    void runOneReturnsUnhealthyForUnknownCheck() {
        HealthCheckRegistry registry = new HealthCheckRegistry();
        HealthCheckRegistry.HealthCheckResult result = registry.runOne("missing");

        assertEquals(HealthCheckRegistry.HealthStatus.UNHEALTHY, result.status());
        assertTrue(result.message().contains("missing"));
        assertNotNull(result.details());
    }
}

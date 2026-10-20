package me.majhrs16.suite.performance.profiling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceProfilerTest {

    @Test
    void aggregatesMethodSamples() {
        PerformanceProfiler profiler = new PerformanceProfiler();
        profiler.recordMethodExecution("translate", 1_000);
        profiler.recordMethodExecution("translate", 3_000);

        ProfilingReport report = profiler.generateReport();
        MethodProfile profile = report.methodProfiles().get("translate");

        assertNotNull(profile);
        assertEquals(2, profile.callCount());
        assertEquals(4_000, profile.totalTimeNanos());
        assertEquals(1_000, profile.minTimeNanos());
        assertEquals(3_000, profile.maxTimeNanos());
        assertEquals(2_000.0, profile.avgTimeNanos());
        assertNotNull(report.memorySnapshot());
        assertNotNull(report.systemMetrics());
        assertNotNull(report.bottlenecks());
    }

    @Test
    void stopWithoutStartReturnsEmptyReport() {
        PerformanceProfiler profiler = new PerformanceProfiler();
        ProfilingReport report = profiler.stopProfiling();

        assertTrue(report.methodProfiles().isEmpty());
        assertEquals(0, report.totalCpuTimeNanos());
    }

    @Test
    void startAndStopLifecycleProducesReport() {
        PerformanceProfiler profiler = new PerformanceProfiler();
        profiler.setSamplingInterval(10);
        profiler.startProfiling();
        profiler.recordMethodExecution("work", 500);
        ProfilingReport report = profiler.stopProfiling();

        assertNotNull(report);
        assertEquals(1, report.methodProfiles().size());
        assertEquals("work", report.methodProfiles().keySet().iterator().next());
        assertFalse(report.methodProfiles().containsKey("missing"));
    }
}

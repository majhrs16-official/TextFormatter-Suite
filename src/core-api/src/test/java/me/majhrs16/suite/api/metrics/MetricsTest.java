package me.majhrs16.suite.api.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MetricsTest {

    private MetricsSink installed;

    @AfterEach
    void cleanup() {
        if (installed != null) {
            Metrics.uninstall(installed);
        }
    }

    @Test
    void recordsWhenSinkInstalledAndDoesNothingAfterUninstall() {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        installed = (sink, result) -> {
            if ("success".equals(result)) {
                success.incrementAndGet();
            } else {
                failed.incrementAndGet();
            }
        };

        Metrics.recordSyncSent("websocket", "success");
        assertEquals(0, success.get(), "no sink installed yet");

        Metrics.install(installed);
        Metrics.recordSyncSent("websocket", "success");
        Metrics.recordSyncSent("websocket", "failed");
        assertEquals(1, success.get());
        assertEquals(1, failed.get());

        Metrics.uninstall(installed);
        Metrics.recordSyncSent("websocket", "success");
        assertEquals(1, success.get(), "uninstalled sink must not receive calls");
        installed = null;
    }

    @Test
    void uninstallOnlyRemovesTheInstalledSink() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        MetricsSink a = (sink, result) -> first.incrementAndGet();
        MetricsSink b = (sink, result) -> second.incrementAndGet();

        Metrics.install(a);
        Metrics.uninstall(b);
        Metrics.recordSyncSent("websocket", "success");
        assertEquals(1, first.get(), "a different sink must not uninstall the active one");

        Metrics.uninstall(a);
        Metrics.install(b);
        installed = b;
        Metrics.recordSyncSent("websocket", "success");
        assertEquals(1, second.get());
    }
}

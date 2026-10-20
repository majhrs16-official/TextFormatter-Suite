package me.majhrs16.suite.observability.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCollectorTest {

    @Test
    void dynamicCountersAreKeyedByLabels() {
        MetricsCollector.incrementCounter("audit.counter", "a", "1");
        MetricsCollector.incrementCounter("audit.counter", "a", "1");
        MetricsCollector.incrementCounter("audit.counter", "b", "2");

        assertTrue(MetricsCollector.getCounter("audit.counter", "a", "1") >= 2);
        assertTrue(MetricsCollector.getCounter("audit.counter", "b", "2") >= 1);
        assertTrue(MetricsCollector.getCounter("audit.counter", "missing") == 0);
    }

    @Test
    void recordsMessagesAndExposesThePrometheusRegistry() {
        MetricsCollector.recordMessage("chat", "global", "out", "ok");
        MetricsCollector.recordMessageLatency("chat", "global", 0.002);
        MetricsCollector.recordTranslation("google", "en", "es", "ok");
        MetricsCollector.recordTranslationLatency("google", 0.003);
        MetricsCollector.recordSyncSent("discord", "ok");
        MetricsCollector.recordSyncLatency("discord", 0.004);
        MetricsCollector.recordRateLimitRejected("global");
        MetricsCollector.setRateLimitUsage("global", 4);
        MetricsCollector.recordRuleMatch("rule-1", "global");
        MetricsCollector.setChannelPending("global", 2);
        MetricsCollector.updateJvmMetrics();

        assertNotNull(MetricsCollector.registry());
        Double value = MetricsCollector.registry().getSampleValue(
            "textformatter_messages_total",
            new String[]{"type", "channel", "direction", "result"},
            new String[]{"chat", "global", "out", "ok"}
        );
        assertNotNull(value);
        assertTrue(value >= 1.0);
        assertTrue(MetricsCollector.startTime() > 0);
    }
}

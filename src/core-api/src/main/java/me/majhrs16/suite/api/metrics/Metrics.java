package me.majhrs16.suite.api.metrics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide facade for optional metrics.
 *
 * <p>The observability package installs a {@link MetricsSink} while it is
 * active. Modules in any classloader share this facade because {@code core-api}
 * is parent-first, so a sync package does not need a compile or runtime
 * dependency on the metrics implementation.</p>
 */
public final class Metrics {

    private static final AtomicReference<MetricsSink> SINK = new AtomicReference<>();

    private Metrics() {
    }

    /**
     * Installs the active metrics sink.
     *
     * @param sink sink to install, must not be null
     */
    public static void install(MetricsSink sink) {
        SINK.set(Objects.requireNonNull(sink, "sink"));
    }

    /**
     * Removes the sink only if it is still the installed one.
     *
     * @param sink sink to remove
     */
    public static void uninstall(MetricsSink sink) {
        SINK.compareAndSet(sink, null);
    }

    /**
     * Records a sync delivery, or does nothing when metrics are not installed.
     *
     * @param sink   sink name
     * @param result {@code success} or {@code failed}
     */
    public static void recordSyncSent(String sink, String result) {
        MetricsSink current = SINK.get();
        if (current != null) {
            current.recordSyncSent(sink, result);
        }
    }
}

package me.majhrs16.suite.api.metrics;

/**
 * Optional metrics sink installed by the observability package.
 *
 * <p>Modules call {@link Metrics} instead of a concrete metrics library so they
 * do not depend on the observability implementation or on Prometheus. When no
 * sink is installed, the calls are no-ops.</p>
 */
public interface MetricsSink {

    /**
     * Records the result of a sync sink delivery.
     *
     * @param sink   sink name, for example {@code websocket}
     * @param result {@code success} or {@code failed}
     */
    void recordSyncSent(String sink, String result);
}

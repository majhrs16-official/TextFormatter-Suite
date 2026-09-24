package me.majhrs16.suite.observability.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.CollectorRegistry;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central metrics collector for the suite using Prometheus client library.
 * Thread-safe, exposes metrics via {@link #registry()}.
 */
public final class MetricsCollector {

    private static final CollectorRegistry REGISTRY = new CollectorRegistry();

    // Message metrics
    public static final Counter MESSAGES_TOTAL = Counter.build()
        .name("textformatter_messages_total")
        .help("Total number of messages processed")
        .labelNames("type", "channel", "direction", "result")
        .register(REGISTRY);

    public static final Histogram MESSAGE_LATENCY = Histogram.build()
        .name("textformatter_message_latency_seconds")
        .help("Message processing latency in seconds")
        .labelNames("type", "channel")
        .buckets(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0)
        .register(REGISTRY);

    // Translation metrics
    public static final Counter TRANSLATIONS_TOTAL = Counter.build()
        .name("textformatter_translations_total")
        .help("Total number of translations performed")
        .labelNames("provider", "source_lang", "target_lang", "result")
        .register(REGISTRY);

    public static final Histogram TRANSLATION_LATENCY = Histogram.build()
        .name("textformatter_translation_latency_seconds")
        .help("Translation latency in seconds")
        .labelNames("provider")
        .buckets(0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
        .register(REGISTRY);

    public static final Counter TRANSLATION_CACHE_HITS = Counter.build()
        .name("textformatter_translation_cache_hits_total")
        .help("Translation cache hits")
        .register(REGISTRY);

    public static final Counter TRANSLATION_CACHE_MISSES = Counter.build()
        .name("textformatter_translation_cache_misses_total")
        .help("Translation cache misses")
        .register(REGISTRY);

    // Sync sink metrics
    public static final Counter SYNC_MESSAGES_SENT = Counter.build()
        .name("textformatter_sync_sent_total")
        .help("Total messages sent to sync sinks")
        .labelNames("sink", "result")
        .register(REGISTRY);

    public static final Histogram SYNC_LATENCY = Histogram.build()
        .name("textformatter_sync_latency_seconds")
        .help("Sync sink send latency in seconds")
        .labelNames("sink")
        .buckets(0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
        .register(REGISTRY);

    // Rate limiter metrics
    public static final Counter RATE_LIMIT_REJECTED = Counter.build()
        .name("textformatter_rate_limit_rejected_total")
        .help("Messages rejected by rate limiter")
        .labelNames("channel")
        .register(REGISTRY);

    public static final Gauge RATE_LIMIT_CURRENT = Gauge.build()
        .name("textformatter_rate_limit_current")
        .help("Current rate limit usage per channel")
        .labelNames("channel")
        .register(REGISTRY);

    // iFlow rule metrics
    public static final Counter RULE_MATCHES = Counter.build()
        .name("textformatter_rule_matches_total")
        .help("Number of times each rule matched")
        .labelNames("rule_id", "target")
        .register(REGISTRY);

    // Channel metrics
    public static final Gauge CHANNEL_MESSAGES_PENDING = Gauge.build()
        .name("textformatter_channel_pending")
        .help("Messages pending delivery per channel")
        .labelNames("channel")
        .register(REGISTRY);

    // JVM metrics
    public static final Gauge JVM_THREADS = Gauge.build()
        .name("jvm_threads_current")
        .help("Current number of JVM threads")
        .register(REGISTRY);

    public static final Gauge JVM_MEMORY_HEAP_USED = Gauge.build()
        .name("jvm_memory_heap_used_bytes")
        .help("JVM heap memory used in bytes")
        .register(REGISTRY);

    public static final Gauge JVM_MEMORY_HEAP_MAX = Gauge.build()
        .name("jvm_memory_heap_max_bytes")
        .help("JVM heap memory max in bytes")
        .register(REGISTRY);

    // Uptime
    public static final Gauge UPTIME_SECONDS = Gauge.build()
        .name("textformatter_uptime_seconds")
        .help("Uptime in seconds")
        .register(REGISTRY);

    private static final long START_TIME = System.currentTimeMillis();

    // Internal state for dynamic metrics
    private static final ConcurrentHashMap<String, AtomicLong> dynamicCounters = new ConcurrentHashMap<>();

    private MetricsCollector() {}

    /**
     * Returns the Prometheus CollectorRegistry for exposing metrics.
     */
    public static CollectorRegistry registry() {
        return REGISTRY;
    }

    /**
     * Records a message processed.
     */
    public static void recordMessage(String type, String channel, String direction, String result) {
        MESSAGES_TOTAL.labels(type, channel, direction, result).inc();
    }

    /**
     * Records message processing latency.
     */
    public static void recordMessageLatency(String type, String channel, double seconds) {
        MESSAGE_LATENCY.labels(type, channel).observe(seconds);
    }

    /**
     * Records a translation performed.
     */
    public static void recordTranslation(String provider, String sourceLang, String targetLang, String result) {
        TRANSLATIONS_TOTAL.labels(provider, sourceLang, targetLang, result).inc();
    }

    /**
     * Records translation latency.
     */
    public static void recordTranslationLatency(String provider, double seconds) {
        TRANSLATION_LATENCY.labels(provider).observe(seconds);
    }

    /**
     * Records a sync message sent.
     */
    public static void recordSyncSent(String sink, String result) {
        SYNC_MESSAGES_SENT.labels(sink, result).inc();
    }

    /**
     * Records sync latency.
     */
    public static void recordSyncLatency(String sink, double seconds) {
        SYNC_LATENCY.labels(sink).observe(seconds);
    }

    /**
     * Records a rate limit rejection.
     */
    public static void recordRateLimitRejected(String channel) {
        RATE_LIMIT_REJECTED.labels(channel).inc();
    }

    /**
     * Updates current rate limit usage for a channel.
     */
    public static void setRateLimitUsage(String channel, long usage) {
        RATE_LIMIT_CURRENT.labels(channel).set(usage);
    }

    /**
     * Records a rule match.
     */
    public static void recordRuleMatch(String ruleId, String target) {
        RULE_MATCHES.labels(ruleId, target).inc();
    }

    /**
     * Updates pending messages for a channel.
     */
    public static void setChannelPending(String channel, long pending) {
        CHANNEL_MESSAGES_PENDING.labels(channel).set(pending);
    }

    /**
     * Updates JVM metrics (call periodically).
     */
    public static void updateJvmMetrics() {
        JVM_THREADS.set(Thread.activeCount());
        JVM_MEMORY_HEAP_USED.set(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        JVM_MEMORY_HEAP_MAX.set(Runtime.getRuntime().maxMemory());
        UPTIME_SECONDS.set((System.currentTimeMillis() - START_TIME) / 1000.0);
    }

    /**
     * Creates or increments a dynamic counter.
     */
    public static void incrementCounter(String name, String... labels) {
        String key = name + "|" + String.join("|", labels);
        dynamicCounters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Gets a dynamic counter value.
     */
    public static long getCounter(String name, String... labels) {
        String key = name + "|" + String.join("|", labels);
        AtomicLong counter = dynamicCounters.get(key);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Returns the start time of the metrics collector.
     */
    public static long startTime() {
        return START_TIME;
    }
}
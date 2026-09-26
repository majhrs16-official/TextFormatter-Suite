package me.majhrs16.suite.iflow.channel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Sliding-window per-second limiter keyed by {@code (channelPath, actorUuid)}.
 *
 * <p>A channel advertises a budget (messages per second) via
 * {@code rateLimitPerSecond}; this limiter enforces it independently for each
 * emitter so a chatty player cannot starve the channel. Clock is injected for
 * deterministic tests.</p>
 */
public final class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ScheduledExecutorService purger;
    private final ReentrantReadWriteLock bucketsLock = new ReentrantReadWriteLock();

    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final long IDLE_NANOS = 5 * WINDOW_NANOS;

    public RateLimiter() {
        this(System::nanoTime);
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
        this.purger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-purge");
            t.setDaemon(true);
            return t;
        });
        purger.scheduleAtFixedRate(() -> purgeIdle(clock.nanoTime()), 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Tries to consume one ticket for the key with the given capacity (messages per second).
     *
     * @param key the rate limit key (channel + actor)
     * @param capacity the maximum messages per second for this key
     * @return {@code true} when within budget, {@code false} when throttled;
     *         a non-zero wait in nanoSeconds is implied by the remaining
     *         window.
     */
    public boolean tryAcquire(String key, int capacity) {
        long now = clock.nanoTime();
        Bucket bucket;
        bucketsLock.readLock().lock();
        try {
            bucket = buckets.get(key);
            if (bucket == null) {
                bucketsLock.readLock().unlock();
                bucketsLock.writeLock().lock();
                try {
                    bucket = buckets.get(key);
                    if (bucket == null) {
                        bucket = new Bucket(capacity);
                        buckets.put(key, bucket);
                    }
                } finally {
                    bucketsLock.writeLock().unlock();
                    bucketsLock.readLock().lock();
                }
            }
            synchronized (bucket) {
                bucket.refill(now);
                if (bucket.tokens >= 1F) {
                    bucket.tokens -= 1F;
                    bucket.lastFill = now;
                    return true;
                }
                return false;
            }
        } finally {
            bucketsLock.readLock().unlock();
        }
    }

    /** Evicts buckets that have been idle for at least {@link #IDLE_NANOS}. */
    private void purgeIdle(long now) {
        long cutoff = now - IDLE_NANOS;
        bucketsLock.writeLock().lock();
        try {
            buckets.entrySet().removeIf(e -> e.getValue().lastFill <= cutoff);
        } finally {
            bucketsLock.writeLock().unlock();
        }
    }

    public void close() {
        if (purger != null) {
            purger.shutdownNow();
        }
    }

    private static final class Bucket {
        private final float maxTokens;
        private float tokens;
        private long lastFill;

        Bucket(float maxTokens) {
            this.maxTokens = maxTokens;
            this.tokens = maxTokens;
        }

        void refill(long now) {
            long elapsed = now - lastFill;
            if (elapsed <= 0) {
                return;
            }
            long windows = elapsed / WINDOW_NANOS;
            if (windows > 0) {
                float gain = windows * maxTokens;
                tokens = Math.min(maxTokens, tokens + gain);
                lastFill += windows * WINDOW_NANOS;
            }
        }
    }

    @FunctionalInterface
    interface Clock {
        long nanoTime();
    }

    /** For tests: advances the clock by whole windows. */
    public long nanosUntilNextWindow() {
        return WINDOW_NANOS;
    }
}

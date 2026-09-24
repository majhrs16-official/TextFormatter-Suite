package me.majhrs16.suite.performance.optimization;

import me.majhrs16.suite.api.spi.PluginLogger;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.lang.ref.ReferenceQueue;
import java.util.stream.Collectors;

/**
 * Intelligent cache optimizer with adaptive eviction policies.
 * Supports LRU, LFU, and adaptive policies based on access patterns.
 */
public final class CacheOptimizer<K, V> {

    private final PluginLogger logger;
    private final int maxSize;
    private final EvictionPolicy policy;
    private final long ttlMillis;
    
    // Main cache
    private final ConcurrentHashMap<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    
    // Access tracking for LFU - using LongAdder for thread-safe counting
    private final ConcurrentHashMap<K, LongAdder> accessCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, Long> lastAccess = new ConcurrentHashMap<>();
    
    // LRU tracking
    private final ConcurrentLinkedQueue<K> accessOrder = new ConcurrentLinkedQueue<>();
    
    // TTL cleanup
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CacheOptimizer-TTL-Cleanup");
        t.setDaemon(true);
        return t;
    });
    
    // Metrics
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong evictionsTTL = new AtomicLong(0);

    public enum EvictionPolicy {
        LRU,    // Least Recently Used
        LFU,    // Least Frequently Used
        ADAPTIVE, // Adapts based on access patterns
        TTL     // Time-based only
    }

    public CacheOptimizer() {
        this(null, 10000, EvictionPolicy.ADAPTIVE, 3600000); // 1 hour default TTL
    }

    public CacheOptimizer(PluginLogger logger, int maxSize, EvictionPolicy policy, long ttlMillis) {
        this.logger = logger;
        this.maxSize = maxSize;
        this.policy = policy;
        this.ttlMillis = ttlMillis;
    }

    /**
     * Gets a value from cache, or computes it if absent.
     */
    public V getOrCompute(K key, Supplier<V> supplier) {
        CacheEntry<V> entry = cache.get(key);
        long now = System.currentTimeMillis();
        
        if (entry != null && !entry.isExpired()) {
            recordHit(key);
            return entry.value;
        }
        
        misses.incrementAndGet();
        V value = supplier.get();
        put(key, value);
        return value;
    }

    /**
     * Puts a value in the cache.
     */
    public void put(K key, V value) {
        put(key, value, ttlMillis);
    }

    /**
     * Puts a value with custom TTL.
     */
    public void put(K key, V value, long ttlMillis) {
        long now = System.currentTimeMillis();
        long expiry = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : Long.MAX_VALUE;
        
        // Check capacity
        if (cache.size() >= maxSize && !cache.containsKey(key)) {
            evict();
        }
        
        CacheEntry<V> entry = new CacheEntry<>(value, System.currentTimeMillis() + ttlMillis);
        cache.put(key, entry);
        recordAccess(key);
    }

    /**
     * Gets a value without recording a miss.
     */
    public Optional<V> get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            recordHit(key);
            return Optional.of(entry.value);
        }
        return Optional.empty();
    }

    /**
     * Invalidates a specific key.
     */
    public void invalidate(K key) {
        cache.remove(key);
        accessCounts.remove(key);
        lastAccess.remove(key);
        accessOrder.remove(key);
    }

    /**
     * Clears the entire cache.
     */
    public void clear() {
        cache.clear();
        accessCounts.clear();
        lastAccess.clear();
        accessOrder.clear();
    }

    /**
     * Gets a value or computes it if absent (with custom TTL).
     */
    public V getOrCompute(K key, Supplier<V> supplier, long ttlMillis) {
        CacheEntry<V> entry = cache.get(key);
        long now = System.currentTimeMillis();
        
        if (entry != null && !entry.isExpired()) {
            recordHit(key);
            return entry.value;
        }
        
        misses.incrementAndGet();
        V value = supplier.get();
        put(key, value, ttlMillis);
        return value;
    }

    // ============================================================
    // Cache Statistics
    // ============================================================

    public CacheStats getStats() {
        long total = hits.get() + misses.get();
        double hitRate = total > 0 ? (double) hits.get() / total : 0.0;
        
        return new CacheStats(
            cache.size(),
            maxSize,
            hits.get(),
            misses.get(),
            hitRate,
            evictions.get(),
            evictionsTTL.get()
        );
    }

    /**
     * Gets the top N most accessed keys.
     */
    public List<Map.Entry<K, Long>> getTopAccessed(int n) {
        return accessCounts.entrySet().stream()
            .sorted(Map.Entry.<K, LongAdder>comparingByValue(
                Comparator.comparingLong(LongAdder::longValue)
            ).reversed())
            .limit(n)
            .map(e -> Map.entry(e.getKey(), e.getValue().longValue()))
            .collect(Collectors.toList());
    }

    /**
     * Gets the least recently used keys.
     */
    public List<K> getLeastRecentlyUsed(int n) {
        return new ArrayList<>(accessOrder).stream()
            .limit(n)
            .collect(Collectors.toList());
    }

    /**
     * Warms up the cache with precomputed values.
     */
    public void warmUp(Map<K, V> entries) {
        for (Map.Entry<K, V> entry : entries.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    // ============================================================
    // Private Implementation
    // ============================================================

    private void recordHit(K key) {
        hits.incrementAndGet();
        recordAccess(key);
    }

    private void recordMiss(K key) {
        misses.incrementAndGet();
    }

    private void recordAccess(K key) {
        long now = System.currentTimeMillis();
        accessCounts.computeIfAbsent(key, k -> new LongAdder()).increment();
        lastAccess.put(key, System.currentTimeMillis());
        
        // Update LRU order
        accessOrder.remove(key);
        accessOrder.offer(key);
    }

    private void evict() {
        if (cache.isEmpty()) return;
        
        K keyToEvict = selectVictim();
        if (keyToEvict != null) {
            cache.remove(keyToEvict);
            accessCounts.remove(keyToEvict);
            lastAccess.remove(keyToEvict);
            accessOrder.remove(keyToEvict);
            evictions.incrementAndGet();
        }
    }

    private K selectVictim() {
        switch (policy) {
            case LRU:
                return accessOrder.poll();
            case LFU:
                return accessCounts.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().longValue()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            case TTL: {
                long now = System.currentTimeMillis();
                return cache.entrySet().stream()
                    .filter(e -> e.getValue().isExpired())
                    .findFirst()
                    .map(Map.Entry::getKey)
                    .orElse(null);
            }
            case ADAPTIVE:
                return selectAdaptiveVictim();
            default:
                return null;
        }
    }

    private K selectAdaptiveVictim() {
        long now = System.currentTimeMillis();
        
        // Score each entry: lower score = better candidate for eviction
        // Score = (accessCount * 0.3) + (recency * 0.7) where recency = now - lastAccess
        return cache.entrySet().stream()
            .min(Comparator.comparingDouble(e -> {
                long accesses = accessCounts.getOrDefault(e.getKey(), new LongAdder()).longValue();
                long lastAccessTime = lastAccess.getOrDefault(e.getKey(), 0L);
                double recencyScore = (now - lastAccessTime) / 1000.0; // seconds since access
                return (accesses * 0.3) + (recencyScore * 0.7);
            }))
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    private static class CacheEntry<V> {
        final V value;
        final long expiryTime;
        final long createdAt;

        CacheEntry(V value, long expiryTime) {
            this.value = value;
            this.expiryTime = expiryTime;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return expiryTime != Long.MAX_VALUE && System.currentTimeMillis() > expiryTime;
        }
    }

    public record CacheStats(
        int currentSize,
        int maxSize,
        long hits,
        long misses,
        double hitRate,
        long evictions,
        long evictionsTTL
    ) {}
}
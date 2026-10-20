package me.majhrs16.suite.performance.optimization;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheOptimizerTest {

    @Test
    void computesOnlyOnCacheMiss() {
        CacheOptimizer<String, String> cache =
            new CacheOptimizer<>(null, 4, CacheOptimizer.EvictionPolicy.LRU, 60_000);
        AtomicInteger computes = new AtomicInteger();

        assertEquals("value", cache.getOrCompute("key", () -> {
            computes.incrementAndGet();
            return "value";
        }));
        assertEquals("value", cache.getOrCompute("key", () -> {
            computes.incrementAndGet();
            return "other";
        }));

        assertEquals(1, computes.get());
        assertEquals("value", cache.get("key").orElseThrow());
    }

    @Test
    void evictsWhenCapacityIsExceeded() {
        CacheOptimizer<String, String> cache =
            new CacheOptimizer<>(null, 2, CacheOptimizer.EvictionPolicy.LRU, 60_000);

        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");

        CacheOptimizer.CacheStats stats = cache.getStats();
        assertEquals(2, stats.currentSize());
        assertEquals(2, stats.maxSize());
        assertTrue(stats.evictions() >= 1);
        assertFalse(cache.get("a").isPresent());
        assertTrue(cache.get("b").isPresent());
        assertTrue(cache.get("c").isPresent());
    }

    @Test
    void invalidatesAndClearsEntries() {
        CacheOptimizer<String, String> cache =
            new CacheOptimizer<>(null, 4, CacheOptimizer.EvictionPolicy.LFU, 60_000);
        cache.warmUp(Map.of("x", "X", "y", "Y"));

        assertTrue(cache.get("x").isPresent());
        cache.invalidate("x");
        assertFalse(cache.get("x").isPresent());
        assertTrue(cache.get("y").isPresent());

        cache.clear();
        assertEquals(0, cache.getStats().currentSize());
        assertFalse(cache.get("y").isPresent());
    }

    @Test
    void exposesAccessStatistics() {
        CacheOptimizer<String, String> cache =
            new CacheOptimizer<>(null, 4, CacheOptimizer.EvictionPolicy.ADAPTIVE, 60_000);
        cache.put("hot", "H");
        cache.get("hot");
        cache.get("hot");
        cache.get("cold");

        assertFalse(cache.getTopAccessed(1).isEmpty());
        assertEquals("hot", cache.getTopAccessed(1).get(0).getKey());
        assertEquals(1, cache.getLeastRecentlyUsed(1).size());
        assertTrue(cache.getStats().hits() >= 2);
    }
}

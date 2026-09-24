package me.majhrs16.suite.performance.optimization;

import me.majhrs16.suite.api.spi.PluginLogger;

import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Memory optimization utilities for the TextFormatter Suite.
 * Provides cache eviction, object pooling, weak reference management, and memory pressure handling.
 */
public final class MemoryOptimizer {

    private final PluginLogger logger;
    private final long maxHeapBytes;
    private final double targetHeapUsagePercent;
    private final double criticalThreshold = 0.85; // 85%
    private final double warningThreshold = 0.70;  // 70%
    
    // Weak reference cache for temporary objects
    private final ReferenceQueue<Object> refQueue = new ReferenceQueue<>();
    private final Map<String, WeakReference<Object>> weakCache = new ConcurrentHashMap<>();
    
    // Object pools for frequently allocated objects
    private final Map<Class<?>, ObjectPool<?>> objectPools = new ConcurrentHashMap<>();
    
    // Memory monitoring
    private final ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MemoryOptimizer-Monitor");
        t.setDaemon(true);
        return t;
    });
    
    // Metrics
    private final java.util.concurrent.atomic.AtomicLong allocationsCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong deallocationsCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong gcCount = new java.util.concurrent.atomic.AtomicLong(0);

    public MemoryOptimizer() {
        this(null, Runtime.getRuntime().maxMemory(), 0.75);
    }

    public MemoryOptimizer(PluginLogger logger, long maxHeapBytes, double targetHeapUsagePercent) {
        this.logger = logger;
        this.maxHeapBytes = maxHeapBytes;
        this.targetHeapUsagePercent = Math.max(0.5, Math.min(0.9, targetHeapUsagePercent));
    }

    /**
     * Starts the memory optimizer background tasks.
     */
    public void start() {
        // Monitor memory pressure every 30 seconds
        monitorExecutor.scheduleAtFixedRate(this::checkMemoryPressure, 30, 30, TimeUnit.SECONDS);
        
        // Clean reference queue every 60 seconds
        monitorExecutor.scheduleAtFixedRate(this::cleanReferenceQueue, 60, 60, TimeUnit.SECONDS);
        
        logInfo("Memory optimizer started");
    }

    /**
     * Stops the memory optimizer.
     */
    public void stop() {
        monitorExecutor.shutdown();
        try {
            if (!monitorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            monitorExecutor.shutdownNow();
        }
    }

    // ============================================================
    // Object Pooling
    // ============================================================

    /**
     * Gets or creates an object pool for the given class.
     */
    @SuppressWarnings("unchecked")
    public <T> ObjectPool<T> getOrCreatePool(Class<T> clazz, Supplier<T> factory, int maxSize) {
        return (ObjectPool<T>) objectPools.computeIfAbsent(clazz, 
            k -> new ObjectPool<>(factory, maxSize));
    }

    /**
     * Returns an object to its pool.
     */
    public <T> void returnToPool(T object, Class<T> clazz) {
        ObjectPool<T> pool = (ObjectPool<T>) objectPools.get(clazz);
        if (pool != null) {
            pool.release(object);
        }
    }

    /**
     * Generic object pool implementation.
     */
    public static final class ObjectPool<T> {
        private final Supplier<T> factory;
        private final int maxSize;
        private final ConcurrentLinkedQueue<T> pool = new ConcurrentLinkedQueue<>();
        private final java.util.concurrent.atomic.AtomicInteger createdCount = new java.util.concurrent.atomic.AtomicInteger(0);

        public ObjectPool(Supplier<T> factory, int maxSize) {
            this.factory = factory;
            this.maxSize = maxSize;
        }

        public T acquire() {
            T obj = pool.poll();
            if (obj == null) {
                obj = factory.get();
                createdCount.incrementAndGet();
            }
            return obj;
        }

        public void release(T obj) {
            if (pool.size() < maxSize) {
                pool.offer(obj);
            }
        }

        public int size() { return pool.size(); }
        public int createdCount() { return createdCount.get(); }
    }

    // ============================================================
    // Weak Reference Cache
    // ============================================================

    /**
     * Puts an object in the weak cache.
     */
    public void putWeak(String key, Object value) {
        weakCache.put(key, new WeakReference<>(value, refQueue));
    }

    /**
     * Gets an object from the weak cache.
     */
    @SuppressWarnings("unchecked")
    public <T> T getWeak(String key) {
        WeakReference<Object> ref = weakCache.get(key);
        return ref != null ? (T) ref.get() : null;
    }

    /**
     * Removes an entry from the weak cache.
     */
    public void removeWeak(String key) {
        weakCache.remove(key);
    }

    // ============================================================
    // Memory Pressure Handling
    // ============================================================

    /**
     * Checks memory pressure and takes action if needed.
     */
    private void checkMemoryPressure() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double usagePercent = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();

        if (usagePercent > criticalThreshold) {
            logWarn("Critical memory pressure: " + String.format("%.1f%%", usagePercent * 100));
            emergencyCleanup();
        } else if (usagePercent > warningThreshold) {
            logWarn("High memory usage: " + String.format("%.1f%%", usagePercent * 100));
            proactiveCleanup();
        }
    }

    /**
     * Performs emergency cleanup under critical memory pressure.
     */
    private void emergencyCleanup() {
        logWarn("Performing emergency memory cleanup");
        
        // Clear weak cache
        weakCache.clear();
        cleanReferenceQueue();
        
        // Shrink object pools
        for (ObjectPool<?> pool : objectPools.values()) {
            pool.pool.clear();
        }
        
        // Force GC
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        
        logInfo("Emergency cleanup completed");
    }

    /**
     * Performs proactive cleanup when memory is high.
     */
    private void proactiveCleanup() {
        logInfo("Performing proactive memory cleanup");
        
        // Clean reference queue
        cleanReferenceQueue();
        
        // Reduce pool sizes
        for (ObjectPool<?> pool : objectPools.values()) {
            int targetSize = Math.max(1, pool.maxSize / 2);
            while (pool.pool.size() > targetSize) {
                pool.pool.poll();
            }
        }
        
        // Suggest GC
        System.gc();
    }

    /**
     * Cleans the reference queue.
     */
    private void cleanReferenceQueue() {
        int cleaned = 0;
        while (refQueue.poll() != null) {
            cleaned++;
        }
        if (cleaned > 0) {
            logDebug("Cleaned " + cleaned + " weak references");
        }
    }

    // ============================================================
    // Object Pool Integration Helpers
    // ============================================================

    /**
     * Gets a StringBuilder from the pool.
     */
    public StringBuilder acquireStringBuilder() {
        return getOrCreatePool(StringBuilder.class, StringBuilder::new, 100).acquire();
    }

    /**
     * Returns a StringBuilder to the pool.
     */
    public void releaseStringBuilder(StringBuilder sb) {
        if (sb != null) {
            sb.setLength(0);
            returnToPool(sb, StringBuilder.class);
        }
    }

    /**
     * Gets a byte array from the pool.
     */
    public byte[] acquireByteArray(int size) {
        return getOrCreatePool(byte[].class, () -> new byte[size], 50).acquire();
    }

    /**
     * Returns a byte array to the pool.
     */
    public void releaseByteArray(byte[] array) {
        if (array != null) {
            returnToPool(array, byte[].class);
        }
    }

    // ============================================================
    // Metrics & Reporting
    // ============================================================

    /**
     * Gets current memory statistics.
     */
    public MemoryStats getMemoryStats() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - rt.freeMemory();
        
        return new MemoryStats(
            rt.maxMemory(),
            rt.totalMemory(),
            rt.freeMemory(),
            total - rt.freeMemory(),
            (double) (total - rt.freeMemory()) / rt.maxMemory() * 100,
            allocationsCount.get(),
            deallocationsCount.get(),
            gcCount.get()
        );
    }

    /**
     * Records an allocation.
     */
    public void recordAllocation(long bytes) {
        allocationsCount.addAndGet(bytes);
    }

    /**
     * Records a deallocation.
     */
    public void recordDeallocation(long bytes) {
        deallocationsCount.addAndGet(bytes);
    }

    /**
     * Records a GC event.
     */
    public void recordGc() {
        gcCount.incrementAndGet();
    }

    // ============================================================
    // Shutdown
    // ============================================================

    public void shutdown() {
        stop();
        objectPools.clear();
        weakCache.clear();
    }

    // ============================================================
    // Logging
    // ============================================================

    private void logInfo(String msg) {
        if (logger != null) logger.info("[MemoryOptimizer] " + msg);
        else System.out.println("[MemoryOptimizer] " + msg);
    }

    private void logWarn(String msg) {
        if (logger != null) logger.warn("[MemoryOptimizer] " + msg);
        else System.err.println("[WARN] [MemoryOptimizer] " + msg);
    }

    private void logDebug(String msg) {
        if (logger != null) logger.debug("[MemoryOptimizer] " + msg);
    }

    // ============================================================
    // Data Classes
    // ============================================================

    public record MemoryStats(
        long maxMemory,
        long totalMemory,
        long freeMemory,
        long usedMemory,
        double usagePercent,
        long allocations,
        long deallocations,
        long gcCount
    ) {}
}
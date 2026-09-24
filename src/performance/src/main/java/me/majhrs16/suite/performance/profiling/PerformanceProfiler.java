package me.majhrs16.suite.performance.profiling;

import me.majhrs16.suite.api.spi.PluginLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

// ============================================================
// Data Classes (Records) - Must be defined before use
// ============================================================

record MethodProfile(
    String methodName,
    long totalTimeNanos,
    long callCount,
    long minTimeNanos,
    long maxTimeNanos,
    double avgTimeNanos
) {
    public MethodProfile withSample(long durationNanos) {
        long newTotal = totalTimeNanos + durationNanos;
        long newCount = callCount + 1;
        long newMin = callCount == 0 ? durationNanos : Math.min(minTimeNanos, durationNanos);
        long newMax = Math.max(maxTimeNanos, durationNanos);
        double newAvg = (double) newTotal / newCount;
        return new MethodProfile(methodName, newTotal, newCount, newMin, newMax, newAvg);
    }
}

record MemorySnapshot(
    long heapUsed,
    long heapCommitted,
    long heapMax,
    long nonHeapUsed,
    long nonHeapMax,
    Map<String, Long> bufferPoolUsage,
    GcStats gcStats
) {}

record GcCollectorInfo(
    String name,
    long collectionCount,
    long collectionTime,
    String[] memoryPoolNames
) {}

record GcStats(
    long totalCollections,
    long totalTimeMs
) {
    public double getOverheadPercent(long uptimeMs) {
        return uptimeMs > 0 ? (double) totalTimeMs / uptimeMs * 100 : 0;
    }
    public long getTotalTime() {
        return totalTimeMs;
    }
}

record BufferPoolStats(
    String name,
    long count,
    long memoryUsed,
    long totalCapacity
) {}

record CompilationStats(
    long totalCompilationTimeMs,
    int compilationCount
) {}

record ThreadDump(
    ThreadInfo[] threads,
    int threadCount,
    int peakThreadCount,
    long totalStartedThreads
) {}

record BottleneckAnalysis(
    List<String> issues,
    List<String> recommendations
) {}

record SystemMetrics(
    double cpuLoadPercent,
    long freePhysicalMemory,
    long totalPhysicalMemory,
    long freeSwapSpace,
    long totalSwapSpace,
    int availableProcessors,
    long uptimeMs,
    double processCpuLoadPercent
) {
    public double cpuLoad() {
        return cpuLoadPercent;
    }
}

record ProfilingReport(
    long totalCpuTimeNanos,
    long totalAllocatedMemory,
    long peakMemoryUsage,
    long gcCount,
    long gcTimeMs,
    Map<String, MethodProfile> methodProfiles,
    MemorySnapshot memorySnapshot,
    GcStats gcStats,
    SystemMetrics systemMetrics,
    BottleneckAnalysis bottlenecks
) {}

/**
 * Advanced performance profiler for the TextFormatter Suite.
 * Provides CPU profiling, memory tracking, GC analysis, and bottleneck detection.
 */
public final class PerformanceProfiler {

    private final PluginLogger logger;
    private final ThreadMXBean threadMXBean;
    private final MemoryMXBean memoryMXBean;
    private final List<GarbageCollectorMXBean> gcMXBeans;
    private final OperatingSystemMXBean osMXBean;
    private final RuntimeMXBean runtimeMXBean;
    private final List<BufferPoolMXBean> bufferPoolMXBeans;
    private final CompilationMXBean compilationMXBean;

    // Profiling state
    private final AtomicLong totalCpuTime = new AtomicLong(0);
    private final AtomicLong totalAllocatedMemory = new AtomicLong(0);
    private final AtomicLong peakMemoryUsage = new AtomicLong(0);
    private final AtomicLong gcCount = new AtomicLong(0);
    private final AtomicLong gcTime = new AtomicLong(0);
    private final ConcurrentHashMap<String, MethodProfile> methodProfiles = new ConcurrentHashMap<>();

    // Sampling state
    private volatile boolean profiling = false;
    private Thread samplingThread;
    private final AtomicLong samplingIntervalMs = new AtomicLong(100);

    public PerformanceProfiler() {
        this(null);
    }

    public PerformanceProfiler(PluginLogger logger) {
        this.logger = logger;
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.gcMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.osMXBean = ManagementFactory.getOperatingSystemMXBean();
        this.runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        this.bufferPoolMXBeans = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        this.compilationMXBean = ManagementFactory.getCompilationMXBean();

        // Enable thread CPU time measurement
        if (threadMXBean.isThreadCpuTimeSupported()) {
            threadMXBean.setThreadCpuTimeEnabled(true);
        }
    }

    // ============================================================
    // Profiling Control
    // ============================================================

    /**
     * Starts CPU and memory profiling with sampling.
     */
    public void startProfiling() {
        if (profiling) return;
        
        profiling = true;
        resetCounters();
        
        samplingThread = new Thread(this::samplingLoop, "PerformanceProfiler-Sampler");
        samplingThread.setDaemon(true);
        samplingThread.start();
        
        logInfo("Performance profiling started");
    }

    /**
     * Stops profiling and returns a summary report.
     */
    public ProfilingReport stopProfiling() {
        if (!profiling) {
            return new ProfilingReport(0, 0, 0, 0, 0, Map.of(), null, null, null, null);
        }
        
        profiling = false;
        if (samplingThread != null) {
            samplingThread.interrupt();
            try {
                samplingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        ProfilingReport report = generateReport();
        logInfo("Performance profiling stopped: " + report);
        return report;
    }

    /**
     * Sets the sampling interval in milliseconds.
     */
    public void setSamplingInterval(long intervalMs) {
        samplingIntervalMs.set(Math.max(10, intervalMs));
    }

    // ============================================================
    // Method Profiling
    // ============================================================

    /**
     * Records the execution time of a method.
     */
    public void recordMethodExecution(String methodName, long durationNanos) {
        methodProfiles.compute(methodName, (key, existing) -> {
            if (existing == null) {
                return new MethodProfile(methodName, durationNanos, 1, durationNanos, durationNanos, durationNanos);
            }
            return existing.withSample(durationNanos);
        });
    }

    /**
     * Records a method execution with a supplier.
     */
    public <T> T profile(String methodName, Supplier<T> supplier) {
        long start = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            recordMethodExecution(methodName, System.nanoTime() - start);
        }
    }

    /**
     * Records an async method execution.
     */
    public <T> java.util.concurrent.CompletableFuture<T> profileAsync(String methodName, java.util.concurrent.CompletableFuture<T> future) {
        long start = System.nanoTime();
        return future.whenComplete((result, throwable) -> {
            recordMethodExecution(methodName, System.nanoTime() - start);
        });
    }

    // ============================================================
    // Memory Profiling
    // ============================================================

    /**
     * Takes a memory snapshot.
     */
    public MemorySnapshot takeMemorySnapshot() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        
        long heapUsed = heapUsage.getUsed();
        long heapCommitted = heapUsage.getCommitted();
        long heapMax = heapUsage.getMax();
        long nonHeapUsed = nonHeapUsage.getUsed();
        long nonHeapMax = nonHeapUsage.getMax();
        
        peakMemoryUsage.updateAndGet(current -> Math.max(current, heapUsed + nonHeapUsed));
        totalAllocatedMemory.addAndGet(heapUsed + nonHeapUsed);
        
        return new MemorySnapshot(
            heapUsed, heapCommitted, heapMax,
            nonHeapUsed, nonHeapMax,
            getBufferPoolUsageMap(),
            getGcStats()
        );
    }

    /**
     * Forces garbage collection and returns memory stats.
     */
    public MemorySnapshot forceGcAndSnapshot() {
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return takeMemorySnapshot();
    }

    /**
     * Gets current memory usage percentage.
     */
    public double getMemoryUsagePercent() {
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        if (heap.getMax() == -1) return 0.0;
        return (double) heap.getUsed() / heap.getMax() * 100.0;
    }

    // ============================================================
    // GC Analysis
    // ============================================================

    /**
     * Gets GC statistics.
     */
    public GcStats getGcStats() {
        long totalCollections = 0;
        long totalTime = 0;
        
        for (GarbageCollectorMXBean gc : gcMXBeans) {
            totalCollections += gc.getCollectionCount();
            totalTime += gc.getCollectionTime();
        }
        
        gcCount.set(totalCollections);
        gcTime.set(totalTime);
        
        return new GcStats(totalCollections, totalTime);
    }

    /**
     * Gets detailed GC info for each collector.
     */
    public Map<String, GcCollectorInfo> getDetailedGcInfo() {
        Map<String, GcCollectorInfo> result = new ConcurrentHashMap<>();
        for (GarbageCollectorMXBean gc : gcMXBeans) {
            result.put(gc.getName(), new GcCollectorInfo(
                gc.getName(),
                gc.getCollectionCount(),
                gc.getCollectionTime(),
                gc.getMemoryPoolNames()
            ));
        }
        return result;
    }

    // ============================================================
    // Thread Analysis
    // ============================================================

    /**
     * Gets current thread dump with CPU times.
     */
    public ThreadDump getThreadDump() {
        long[] threadIds = threadMXBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, Integer.MAX_VALUE);
        
        ThreadInfo[] filtered = new ThreadInfo[threadInfos.length];
        for (int i = 0; i < threadInfos.length; i++) {
            ThreadInfo info = threadInfos[i];
            if (info != null && threadMXBean.isThreadCpuTimeSupported()) {
                long cpuTime = threadMXBean.getThreadCpuTime(info.getThreadId());
                // Create new ThreadInfo with CPU time (can't modify original)
                // Just return the info as-is for now
            }
        }
        
        return new ThreadDump(threadInfos, threadMXBean.getThreadCount(), 
            threadMXBean.getPeakThreadCount(), threadMXBean.getTotalStartedThreadCount());
    }

    /**
     * Finds threads with high CPU usage.
     */
    public List<ThreadInfo> findHotThreads(int topN) {
        long[] threadIds = threadMXBean.getAllThreadIds();
        ThreadInfo[] infos = threadMXBean.getThreadInfo(threadIds, Integer.MAX_VALUE);
        
        return java.util.Arrays.stream(infos)
            .filter(info -> info != null)
            .sorted((a, b) -> {
                if (!threadMXBean.isThreadCpuTimeSupported()) return 0;
                long cpuA = threadMXBean.getThreadCpuTime(a.getThreadId());
                long cpuB = threadMXBean.getThreadCpuTime(b.getThreadId());
                return Long.compare(cpuB, cpuA);
            })
            .limit(topN)
            .toList();
    }

    /**
     * Detects potential deadlocks.
     */
    public long[] findDeadlockedThreads() {
        return threadMXBean.findDeadlockedThreads();
    }

    /**
     * Detects threads blocked on monitors.
     */
    public long[] findMonitorDeadlockedThreads() {
        return threadMXBean.findMonitorDeadlockedThreads();
    }

    // ============================================================
    // System Metrics
    // ============================================================

    /**
     * Gets system-level metrics.
     */
    public SystemMetrics getSystemMetrics() {
        OperatingSystemMXBean os = osMXBean;
        
        double cpuLoad = 0;
        long freePhysicalMemory = 0;
        long totalPhysicalMemory = 0;
        long freeSwapSpace = 0;
        long totalSwapSpace = 0;
        int availableProcessors = os.getAvailableProcessors();
        
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            cpuLoad = sunOs.getSystemCpuLoad() * 100;
            freePhysicalMemory = sunOs.getFreePhysicalMemorySize();
            totalPhysicalMemory = sunOs.getTotalPhysicalMemorySize();
            freeSwapSpace = sunOs.getFreeSwapSpaceSize();
            totalSwapSpace = sunOs.getTotalSwapSpaceSize();
        }
        
        return new SystemMetrics(
            cpuLoad,
            freePhysicalMemory,
            totalPhysicalMemory,
            freeSwapSpace,
            totalSwapSpace,
            availableProcessors,
            runtimeMXBean.getUptime(),
            getProcessCpuLoad()
        );
    }

    private double getProcessCpuLoad() {
        if (osMXBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            return sunOs.getProcessCpuLoad() * 100;
        }
        return 0.0;
    }

    // ============================================================
    // Buffer Pool & Compilation
    // ============================================================

    /**
     * Gets buffer pool usage.
     */
    public Map<String, BufferPoolStats> getBufferPoolUsage() {
        Map<String, BufferPoolStats> result = new ConcurrentHashMap<>();
        for (BufferPoolMXBean pool : bufferPoolMXBeans) {
            result.put(pool.getName(), new BufferPoolStats(
                pool.getName(),
                pool.getCount(),
                pool.getMemoryUsed(),
                pool.getTotalCapacity()
            ));
        }
        return result;
    }

    /**
     * Gets JIT compilation stats.
     */
    public CompilationStats getCompilationStats() {
        return new CompilationStats(
            compilationMXBean.getTotalCompilationTime(),
            0 // No direct way to get count in standard API
        );
    }

    // ============================================================
    // Bottleneck Detection
    // ============================================================

    /**
     * Analyzes current performance and detects bottlenecks.
     */
    public BottleneckAnalysis detectBottlenecks() {
        List<String> issues = new java.util.ArrayList<>();
        List<String> recommendations = new java.util.ArrayList<>();
        
        // Memory pressure
        double memUsage = getMemoryUsagePercent();
        if (memUsage > 90) {
            issues.add("Critical memory pressure: " + String.format("%.1f%%", memUsage));
            recommendations.add("Increase heap size or investigate memory leak");
        } else if (memUsage > 75) {
            issues.add("High memory usage: " + String.format("%.1f%%", memUsage));
            recommendations.add("Consider increasing heap or optimizing allocations");
        }
        
        // GC pressure
        GcStats gc = getGcStats();
        if (gc.getTotalTime() > 0) {
            double gcOverhead = (double) gc.getTotalTime() / runtimeMXBean.getUptime() * 100;
            if (gcOverhead > 10) {
                issues.add("High GC overhead: " + String.format("%.1f%%", gcOverhead));
                recommendations.add("Tune GC or reduce allocation rate");
            }
        }
        
        // Thread contention
        long[] deadlocked = findDeadlockedThreads();
        if (deadlocked != null && deadlocked.length > 0) {
            issues.add("Deadlock detected involving " + deadlocked.length + " threads");
            recommendations.add("Review locking order and reduce lock scope");
        }
        
        long[] monitorDeadlocked = findMonitorDeadlockedThreads();
        if (monitorDeadlocked != null && monitorDeadlocked.length > 0) {
            issues.add("Monitor deadlock detected involving " + monitorDeadlocked.length + " threads");
        }
        
        // Thread count
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        if (threadCount > 500) {
            issues.add("High thread count: " + threadCount);
            recommendations.add("Consider thread pool tuning");
        }
        
        // CPU load
        SystemMetrics sys = getSystemMetrics();
        if (sys.cpuLoad() > 80) {
            issues.add("High CPU load: " + String.format("%.1f%%", sys.cpuLoad()));
            recommendations.add("Profile CPU hotspots, consider async processing");
        }
        
        return new BottleneckAnalysis(issues, recommendations);
    }

    // ============================================================
    // Reporting
    // ============================================================

    /**
     * Generates a comprehensive profiling report.
     */
    public ProfilingReport generateReport() {
        MemorySnapshot memory = takeMemorySnapshot();
        GcStats gc = getGcStats();
        SystemMetrics system = getSystemMetrics();
        Map<String, MethodProfile> methods = new ConcurrentHashMap<>(methodProfiles);
        BottleneckAnalysis bottlenecks = detectBottlenecks();
        
        return new ProfilingReport(
            totalCpuTime.get(),
            totalAllocatedMemory.get(),
            peakMemoryUsage.get(),
            gcCount.get(),
            gcTime.get(),
            methods,
            memory,
            gc,
            system,
            bottlenecks
        );
    }

    // ============================================================
    // Internal Methods
    // ============================================================

    private void samplingLoop() {
        while (profiling) {
            try {
                // Sample memory
                takeMemorySnapshot();
                
                // Sample threads
                ThreadInfo[] infos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds(), 100);
                for (ThreadInfo info : infos) {
                    if (info != null && threadMXBean.isThreadCpuTimeSupported()) {
                        long cpuTime = threadMXBean.getThreadCpuTime(info.getThreadId());
                        // Could track per-thread CPU here
                    }
                }
                
                Thread.sleep(samplingIntervalMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logError("Sampling error: " + e.getMessage(), e);
            }
        }
    }

    private void resetCounters() {
        totalCpuTime.set(0);
        totalAllocatedMemory.set(0);
        peakMemoryUsage.set(0);
        gcCount.set(0);
        gcTime.set(0);
        methodProfiles.clear();
    }

    private Map<String, Long> getBufferPoolUsageMap() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        for (BufferPoolMXBean pool : bufferPoolMXBeans) {
            result.put(pool.getName(), pool.getMemoryUsed());
        }
        return result;
    }

    // Logging helpers
    private void logInfo(String msg) {
        if (logger != null) logger.info(msg);
        else System.out.println("[PERF] " + msg);
    }

    private void logError(String msg, Exception e) {
        if (logger != null) logger.error(msg, e);
        else e.printStackTrace();
    }

    // ============================================================
}

package me.majhrs16.suite.performance.optimization;

import me.majhrs16.suite.api.spi.PluginLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.Collectors;

/**
 * Hotspot detector for identifying performance bottlenecks in real-time.
 * Monitors method execution times, allocation rates, and thread contention.
 */
public final class HotspotDetector {

    private final PluginLogger logger;
    private final ThreadMXBean threadMXBean;
    private final MemoryMXBean memoryMXBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    
    // Hotspot tracking
    private final ConcurrentHashMap<String, MethodHotspot> methodHotspots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AllocationHotspot> allocationHotspots = new ConcurrentHashMap<>();
    
    // Configuration
    private final long hotThresholdNanos;
    private final int minSamples;
    private final long samplingIntervalMs;
    
    // Sampling state
    private volatile boolean running = false;
    private Thread samplingThread;
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HotspotDetector-Sampler");
        t.setDaemon(true);
        return t;
    });
    
    // Thread-local stack trace cache
    private final ThreadLocal<Deque<String>> stackTraceCache = ThreadLocal.withInitial(ConcurrentLinkedDeque::new);

    public HotspotDetector() {
        this(null, 10_000_000, 10, 100); // 10ms threshold, 10 min samples, 100ms interval
    }

    public HotspotDetector(PluginLogger logger, long hotThresholdNanos, int minSamples, long samplingIntervalMs) {
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.logger = null;
        
        this.hotThresholdNanos = hotThresholdNanos;
        this.minSamples = minSamples;
        this.samplingIntervalMs = samplingIntervalMs;
    }

    /**
     * Starts hotspot detection.
     */
    public void start() {
        if (running) return;
        
        // Enable thread CPU time measurement
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (threadMXBean.isThreadCpuTimeSupported()) {
            threadMXBean.setThreadCpuTimeEnabled(true);
        }
        
        running = true;
        samplingThread = new Thread(this::samplingLoop, "HotspotDetector-Sampler");
        samplingThread.setDaemon(true);
        samplingThread.start();
    }

    /**
     * Stops hotspot detection.
     */
    public void stop() {
        running = false;
        if (samplingThread != null) {
            samplingThread.interrupt();
            try {
                samplingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Records a method execution time.
     */
    public void recordExecution(String methodName, long durationNanos) {
        methodHotspots.compute(methodName, (key, existing) -> {
            if (existing == null) {
                return new MethodHotspot(methodName);
            }
            existing.addSample(durationNanos);
            return existing;
        });
    }

    /**
     * Records an allocation site.
     */
    public void recordAllocation(String allocationSite, long sizeBytes) {
        allocationHotspots.compute(allocationSite, (key, existing) -> {
            if (existing == null) {
                return new AllocationHotspot(key, sizeBytes);
            }
            existing.addAllocation(sizeBytes);
            return existing;
        });
    }

    /**
     * Records a method call with timing.
     */
    public <T> T profile(String methodName, Supplier<T> supplier) {
        long start = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            recordExecution(Thread.currentThread().getStackTrace()[1].getMethodName(), 
                System.nanoTime() - startTime.get());
        }
    }

    /**
     * Gets the top N hottest methods by total time.
     */
    public List<MethodHotspot> getTopHotspots(int n) {
        return methodHotspots.values().stream()
            .filter(h -> h.getSampleCount() >= 5)
            .sorted(Comparator.comparingLong(MethodHotspot::getTotalTimeNanos).reversed())
            .limit(n)
            .toList();
    }

    /**
     * Gets the top allocation hotspots.
     */
    public List<AllocationHotspot> getTopAllocationHotspots(int n) {
        return allocationHotspots.values().stream()
            .filter(h -> h.getCount() >= 5)
            .sorted(Comparator.comparingLong(AllocationHotspot::getTotalBytes).reversed())
            .limit(n)
            .toList();
    }

    /**
     * Gets all hotspots above threshold.
     */
    public List<MethodHotspot> getAllHotspots() {
        return methodHotspots.values().stream()
            .filter(h -> h.getTotalTimeNanos() > hotThresholdNanos && h.getSampleCount() >= minSamples)
            .sorted(Comparator.comparingLong(MethodHotspot::getTotalTimeNanos).reversed())
            .toList();
    }

    /**
     * Generates a hotspot report.
     */
    public HotspotReport generateReport() {
        List<MethodHotspot> topMethods = getTopHotspots(20);
        List<AllocationHotspot> topAllocations = getTopAllocationHotspots(10);
        
        return new HotspotReport(
            System.currentTimeMillis(),
            methodHotspots.size(),
            allocationHotspots.size(),
            topMethods,
            topAllocations,
            getThreadContention()
        );
    }

    // ============================================================
    // Internal Sampling
    // ============================================================

    private void samplingLoop() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (threadMXBean.isThreadCpuTimeSupported()) {
            threadMXBean.setThreadCpuTimeEnabled(true);
        }

        while (running) {
            try {
                // Sample thread CPU times
                ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
                long[] threadIds = threadMXBean.getAllThreadIds();
                
                for (long tid : threadIds) {
                    ThreadInfo info = threadMXBean.getThreadInfo(tid, 10);
                    if (info != null && info.getThreadState() == Thread.State.RUNNABLE) {
                        long cpuTime = threadMXBean.getThreadCpuTime(info.getThreadId());
                        String methodKey = info.getThreadName() + "::" + 
                            (info.getStackTrace().length > 0 ? info.getStackTrace()[0].getMethodName() : "unknown");
                        
                        long cpuTime = threadMXBean.getThreadCpuTime(info.getThreadId());
                        recordExecution(methodKey, cpuTime);
                    }
                }

                // Sample allocation sites (approximate via memory MXBean)
                // Note: precise allocation tracking requires Java Flight Recorder or async-profiler

            } catch (Exception e) {
                // Ignore sampling errors
            }

            try {
                Thread.sleep(samplingIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Detects thread contention points.
     */
    private List<ContentionPoint> getThreadContention() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
        
        List<ContentionPoint> contentions = new ArrayList<>();
        for (ThreadInfo info : infos) {
            if (info.getLockInfo() != null) {
                contentions.add(new ContentionPoint(
                    info.getThreadName(),
                    info.getLockName(),
                    info.getLockOwnerName(),
                    info.getBlockedTime(),
                    info.getBlockedCount()
                ));
            }
        }
        
        return contentions.stream()
            .sorted(Comparator.comparingLong(ContentionPoint::getBlockedTime).reversed())
            .limit(10)
            .toList();
    }

    // ============================================================
    // Data Classes
    // ============================================================

    public record HotspotReport(
        long timestamp,
        int methodCount,
        int allocationSiteCount,
        List<MethodHotspot> topMethods,
        List<AllocationHotspot> topAllocations,
        List<ContentionPoint> contentionPoints
    ) {}

    public static class MethodHotspot {
        private final String methodName;
        private long totalTimeNanos = 0;
        private long minTimeNanos = Long.MAX_VALUE;
        private long maxTimeNanos = 0;
        private long sampleCount = 0;

        public MethodHotspot(String name) {
            this.methodName = name;
        }

        public synchronized void addSample(long durationNanos) {
            this.totalTimeNanos += durationNanos;
            this.minTimeNanos = Math.min(minTimeNanos, durationNanos);
            this.maxTimeNanos = Math.max(maxTimeNanos, durationNanos);
            this.sampleCount++;
        }

        public String getMethodName() { return methodName; }
        public long getTotalTimeNanos() { return totalTimeNanos; }
        public long getMinTimeNanos() { return minTimeNanos == Long.MAX_VALUE ? 0 : minTimeNanos; }
        public long getMaxTimeNanos() { return maxTimeNanos; }
        public long getSampleCount() { return sampleCount; }
        public double getAverageTimeNanos() { return sampleCount > 0 ? (double) totalTimeNanos / sampleCount : 0; }
    }

    public record AllocationHotspot(
        String site,
        long totalBytes,
        long count,
        long avgSize
    ) {
        public AllocationHotspot(String site, long sizeBytes) {
            this(site, sizeBytes, 1, sizeBytes);
        }

        public synchronized void addAllocation(long sizeBytes) {
            this.totalBytes += sizeBytes;
            this.count++;
        }
    }

    public record ContentionPoint(
        String threadName,
        String lockName,
        String ownerName,
        long blockedTimeMs,
        long blockedCount
    ) {}

    // Thread-local start time for profiling
    private static final ThreadLocal<Long> startTime = ThreadLocal.withInitial(() -> System.nanoTime());
}
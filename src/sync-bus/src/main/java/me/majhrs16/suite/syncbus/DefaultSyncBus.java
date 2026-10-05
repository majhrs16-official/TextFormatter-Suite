package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Default implementation of {@link SyncBus} with full synchronization hub capabilities.
 * <p>
 * Features:
 * <ul>
 *   <li>Global bounded queue with backpressure (caller runs when full)</li>
 *   <li>Message deduplication via ID tracking</li>
 *   <li>Per-sink isolation: each sink has its own queue + worker thread</li>
 *   <li>Bulkhead pattern: sink failures don't cascade</li>
 *   <li>Scheduled delivery with retry/backoff</li>
 *   <li>Metrics and health monitoring</li>
 * </ul>
 */
public final class DefaultSyncBus implements SyncBus {

    // Configuration constants
    private static final int GLOBAL_QUEUE_CAPACITY = 10000;
    private static final int PER_SINK_QUEUE_CAPACITY = 5000;
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 100;
    private static final long MAX_RETRY_DELAY_MS = 30_000;
    private static final long DEDUP_WINDOW_MS = 60_000;
    private static final int DEDUP_MAX_ENTRIES = 50000;

    private final Map<String, SinkContext> sinks = new ConcurrentHashMap<>();
    private final Set<String> sinkNamesSnapshot = new CopyOnWriteArraySet<>();
    private volatile SyncListener inboundListener;
    private final PluginLogger logger;

    // Global queue with backpressure - using Runnable wrapper
    private final BlockingQueue<Runnable> globalQueue;
    private final ThreadPoolExecutor globalExecutor;
    private final ScheduledExecutorService scheduler;

    // Deduplication
    private final ConcurrentMap<String, Long> sentMessageIds = new ConcurrentHashMap<>();
    private final ScheduledFuture<?> dedupCleanupTask;

    // Lifecycle
    private final AtomicInteger started = new AtomicInteger(0);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    // Metrics
    private final AtomicLong totalBroadcast = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong totalRetries = new AtomicLong(0);
    private final AtomicLong totalDeduped = new AtomicLong(0);

    public DefaultSyncBus(PluginLogger logger) {
        this.logger = logger;

        // Global executor with CallerRunsPolicy for backpressure
        this.globalExecutor = new ThreadPoolExecutor(
            2, 8,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(GLOBAL_QUEUE_CAPACITY),
            createThreadFactory("syncbus-global"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        this.globalQueue = globalExecutor.getQueue();

        // Scheduler for retries and dedup cleanup
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            createThreadFactory("syncbus-scheduler"));

        // Deduplication cleanup task
        this.dedupCleanupTask = scheduler.scheduleAtFixedRate(
            this::cleanupDedupCache,
            DEDUP_WINDOW_MS, DEDUP_WINDOW_MS, TimeUnit.MILLISECONDS);

        // Start global processor
        startGlobalProcessor();
    }

    private static ThreadFactory createThreadFactory(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix + "-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        };
    }

    private void startGlobalProcessor() {
        globalExecutor.submit(() -> {
            while (!shuttingDown.get()) {
                try {
                    Runnable task = globalQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("SyncBus: global processor error: " + e.getMessage(), e);
                }
            }
        });
    }

    private void processMessage(Message message) {
        // Deduplication check
        String msgId = message.id().toString();
        long now = System.currentTimeMillis();

        Long previousTime = sentMessageIds.putIfAbsent(msgId, now);
        if (previousTime != null && (now - previousTime) < DEDUP_WINDOW_MS) {
            totalDeduped.incrementAndGet();
            logger.debug("SyncBus: deduplicated message " + msgId);
            return;
        }

        totalBroadcast.incrementAndGet();

        // Fan out to all sinks with per-sink isolation
        for (SinkContext ctx : sinks.values()) {
            ctx.enqueue(message);
        }
    }

    private void submitToGlobalQueue(Message message) {
        globalExecutor.execute(() -> processMessage(message));
    }

    @Override
    public void register(SyncSink sink) {
        String name = sink.name();
        SinkContext existing = sinks.putIfAbsent(name, new SinkContext(sink));
        if (existing != null) {
            throw new IllegalStateException("Sink with name '" + name + "' already registered: " + existing.sink.getClass().getName());
        }

        // Set up listener for inbound messages
        sink.setListener(new SyncListener() {
            @Override
            public void onMessage(SyncSink s, Message message) {
                onInboundMessage(s, message);
            }

            @Override
            public void onDisconnect(SyncSink s, String reason) {
                logger.warn("SyncBus: sink '" + s.name() + "' disconnected: " + reason);
            }
        });

        sinkNamesSnapshot.add(name);
        logger.debug("SyncBus: registered sink '" + name + "' (" + sink.getClass().getSimpleName() + ")");
    }

    @Override
    public boolean unregister(String name) {
        SinkContext removed = sinks.remove(name);
        if (removed != null) {
            sinkNamesSnapshot.remove(name);
            removed.shutdown();
            // Ensure sink.stop() is called to release external resources (sockets, connections, etc.)
            try {
                removed.sink.stop();
            } catch (Exception e) {
                logger.warn("SyncBus: error stopping sink '" + name + "': " + e.getMessage(), e);
            }
            logger.debug("SyncBus: unregistered sink '" + name + "'");
            return true;
        }
        return false;
    }

    @Override
    public Collection<SyncSink> sinks() {
        return Collections.unmodifiableCollection(
            sinks.values().stream().map(ctx -> ctx.sink).toList());
    }

    @Override
    public Set<String> sinkNames() {
        return Collections.unmodifiableSet(new HashSet<>(sinkNamesSnapshot));
    }

    @Override
    public int broadcast(Message message) {
        if (shuttingDown.get()) {
            logger.debug("SyncBus: broadcast called during shutdown");
            return 0;
        }

        if (sinks.isEmpty()) {
            logger.debug("SyncBus: broadcast called but no sinks registered");
            return 0;
        }

        try {
            // Non-blocking offer with backpressure
            boolean offered = globalQueue.offer(() -> processMessage(message), 100, TimeUnit.MILLISECONDS);
            if (!offered) {
                // Queue full - apply backpressure by running in caller thread (CallerRunsPolicy)
                submitToGlobalQueue(message);
            }
            // Return count of sinks that successfully enqueued the message
            int enqueued = 0;
            for (SinkContext ctx : sinks.values()) {
                if (ctx.enqueue(message)) {
                    enqueued++;
                }
            }
            return enqueued;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            totalDropped.incrementAndGet();
            return 0;
        }
    }

    @Override
    public java.util.Map<String, CompletableFuture<Void>> broadcastAsync(Message message) {
        java.util.Map<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();
        
        if (shuttingDown.get() || sinks.isEmpty()) {
            return futures;
        }

        // Submit to global queue to process message
        CompletableFuture<Void> globalFuture = new CompletableFuture<>();
        try {
            globalQueue.offer(() -> {
                try {
                    processMessage(message);
                    globalFuture.complete(null);
                } catch (Exception e) {
                    globalFuture.completeExceptionally(e);
                }
            }, 100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            globalFuture.completeExceptionally(e);
        }

        // Create futures for each sink
        for (SinkContext ctx : sinks.values()) {
            CompletableFuture<Void> sinkFuture = new CompletableFuture<>();
            String sinkName = ctx.sink.name();
            futures.put(sinkName, sinkFuture);

            // Chain sink completion to global future
            globalFuture.whenComplete((v, ex) -> {
                if (ex != null) {
                    sinkFuture.completeExceptionally(ex);
                } else {
                    // Wait for sink to process - we'll poll the queue
                    waitForSinkProcessing(ctx, sinkFuture);
                }
            });
        }

        return futures;
    }

    private void waitForSinkProcessing(SinkContext ctx, CompletableFuture<Void> future) {
        // Schedule a check to see if the sink has processed the message
        scheduler.schedule(() -> {
            if (!ctx.running.get() || ctx.queue.isEmpty()) {
                future.complete(null);
            } else {
                // Re-check after a short delay
                waitForSinkProcessing(ctx, future);
            }
        }, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean sendTo(String sinkName, Message message) {
        SinkContext ctx = sinks.get(sinkName);
        if (ctx == null) {
            logger.warn("SyncBus: sink '" + sinkName + "' not found for directed send");
            return false;
        }
        return ctx.enqueue(message);
    }

    @Override
    public void setInboundListener(SyncListener listener) {
        this.inboundListener = listener;
        logger.debug("SyncBus: inbound listener " + (listener != null ? "set" : "cleared"));
    }

    @Override
    public SyncListener getInboundListener() {
        return inboundListener;
    }

    @Override
    public void start() throws Exception {
        if (started.getAndSet(1) == 1) {
            logger.debug("SyncBus: already started");
            return;
        }

        List<SinkContext> startedContexts = new ArrayList<>();
        try {
            for (SinkContext ctx : sinks.values()) {
                ctx.sink.start();
                ctx.start();
                startedContexts.add(ctx);
                logger.info("SyncBus: started sink '" + ctx.sink.name() + "'");
            }
        } catch (Exception e) {
            // Rollback: stop all sinks that were successfully started in this call
            for (SinkContext ctx : startedContexts) {
                try {
                    ctx.shutdown();
                    ctx.sink.stop();
                } catch (Exception rollbackEx) {
                    logger.warn("SyncBus: error during rollback stop of sink '" + ctx.sink.name() + "': " + rollbackEx.getMessage());
                }
            }
            started.set(0); // Reset started flag so future start() calls can retry
            throw e;
        }
    }

    @Override
    public void stop() {
        if (started.getAndSet(0) == 0) {
            logger.debug("SyncBus: already stopped");
            return;
        }

        shuttingDown.set(true);

        // Stop global processor
        globalExecutor.shutdown();
        try {
            if (!globalExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                globalExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            globalExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Stop scheduler
        dedupCleanupTask.cancel(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Stop all sinks
        for (SinkContext ctx : sinks.values()) {
            try {
                ctx.shutdown();
                logger.info("SyncBus: stopped sink '" + ctx.sink.name() + "'");
            } catch (Exception e) {
                logger.warn("SyncBus: error stopping sink '" + ctx.sink.name() + "': " + e.getMessage());
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return sinks.isEmpty();
    }

    @Override
    public void close() {
        stop();
    }

    // Public metrics for observability
    public long getTotalBroadcast() { return totalBroadcast.get(); }
    public long getTotalDropped() { return totalDropped.get(); }
    public long getTotalRetries() { return totalRetries.get(); }
    public long getTotalDeduped() { return totalDeduped.get(); }
    public int getGlobalQueueSize() { return globalQueue.size(); }
    public int getGlobalQueueRemaining() { return globalQueue.remainingCapacity(); }

    private void onInboundMessage(SyncSink sink, Message message) {
        SyncListener listener = inboundListener;
        if (listener != null) {
            try {
                listener.onMessage(sink, message);
            } catch (Exception e) {
                logger.error("SyncBus: inbound listener error for sink '" + sink.name() + "': " + e.getMessage(), e);
            }
        }
    }

    private void cleanupDedupCache() {
        long cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS;
        sentMessageIds.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        // Prevent unbounded growth
        if (sentMessageIds.size() > DEDUP_MAX_ENTRIES) {
            // Remove oldest entries
            sentMessageIds.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(sentMessageIds.size() - DEDUP_MAX_ENTRIES / 2)
                .forEach(e -> sentMessageIds.remove(e.getKey()));
        }
    }

    /**
     * Per-sink isolation context with dedicated queue and worker.
     */
    private final class SinkContext {
        final SyncSink sink;
        final BlockingQueue<Runnable> queue;
        final ThreadPoolExecutor executor;
        final AtomicBoolean running = new AtomicBoolean(false);

        SinkContext(SyncSink sink) {
            this.sink = sink;
            this.queue = new LinkedBlockingQueue<>(PER_SINK_QUEUE_CAPACITY);
            this.executor = new ThreadPoolExecutor(
                1, 1,
                60L, TimeUnit.SECONDS,
                queue,
                createThreadFactory("syncbus-sink-" + sink.name()),
                new ThreadPoolExecutor.DiscardPolicy()
            );
        }

        boolean enqueue(Message message) {
            if (!running.get() || shuttingDown.get()) {
                return false;
            }
            Runnable task = () -> deliverWithRetry(message, 0);
            boolean offered = queue.offer(task);
            if (!offered) {
                // Sink queue full - drop with metric
                totalDropped.incrementAndGet();
                logger.warn("SyncBus: sink '" + sink.name() + "' queue full, dropping message");
            }
            return offered;
        }

        void start() {
            running.set(true);
            executor.submit(this::processQueue);
        }

        void shutdown() {
            running.set(false);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        private void processQueue() {
            while (running.get() && !shuttingDown.get()) {
                try {
                    Runnable task = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (task == null) continue;

                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("SyncBus: sink '" + sink.name() + "' processor error: " + e.getMessage(), e);
                }
            }
        }

        private void deliverWithRetry(Message message, int attempt) {
            try {
                sink.send(message);
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    totalRetries.incrementAndGet();
                    long delay = Math.min(
                        INITIAL_RETRY_DELAY_MS * (1L << attempt),
                        MAX_RETRY_DELAY_MS
                    );
                    logger.debug("SyncBus: sink '" + sink.name() + "' send failed (attempt " +
                        (attempt + 1) + "/" + MAX_RETRIES + "), retrying in " + delay + "ms: " + e.getMessage());

                    int nextAttempt = attempt + 1;
                    scheduler.schedule(() -> {
                        if (running.get() && !shuttingDown.get()) {
                            queue.offer(() -> deliverWithRetry(message, nextAttempt));
                        }
                    }, delay, TimeUnit.MILLISECONDS);
                } else {
                    totalDropped.incrementAndGet();
                    logger.error("SyncBus: sink '" + sink.name() + "' failed after " + MAX_RETRIES + " retries: " + e.getMessage(), e);
                }
            }
        }
    }
}
package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Default implementation of {@link SyncBus} with full synchronization hub capabilities.
 * <p>
 * Features:
 * <ul>
 *   <li>Global bounded queue retained for the {@code getGlobalQueue*} metrics and orderly shutdown</li>
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
    private static final long DEFAULT_ASYNC_TIMEOUT_MS = 30_000;

    // Lifecycle states
    private enum LifecycleState { NEW, RUNNING, STOPPING, STOPPED }

    private final Map<String, SinkContext> sinks = new ConcurrentHashMap<>();
    private final Set<String> sinkNamesSnapshot = new CopyOnWriteArraySet<>();
    private volatile SyncListener inboundListener;
    private final PluginLogger logger;
    private final long asyncTimeoutMillis;

    // Global bounded queue used by the metrics contract and by the shutdown sentinel.
    private final ThreadPoolExecutor globalExecutor;
    private final BlockingQueue<Runnable> globalQueue;
    private final ScheduledExecutorService scheduler;

    // Deduplication
    private final ConcurrentMap<String, Long> sentMessageIds = new ConcurrentHashMap<>();
    private ScheduledFuture<?> dedupCleanupTask;

    // Lifecycle
    private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.NEW);

    // Metrics
    private final AtomicLong totalBroadcast = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong totalRetries = new AtomicLong(0);
    private final AtomicLong totalDeduped = new AtomicLong(0);

    public DefaultSyncBus(PluginLogger logger) {
        this(logger, DEFAULT_ASYNC_TIMEOUT_MS);
    }

    /**
     * Builds a bus with an explicit async completion timeout.
     *
     * <p>Package-private so tests can exercise the timeout without waiting the
     * production bound.</p>
     */
    DefaultSyncBus(PluginLogger logger, long asyncTimeoutMillis) {
        this.logger = Objects.requireNonNull(logger, "logger");
        if (asyncTimeoutMillis <= 0) {
            throw new IllegalArgumentException("asyncTimeoutMillis must be positive");
        }
        this.asyncTimeoutMillis = asyncTimeoutMillis;

        // Global executor with CallerRunsPolicy for backpressure
        this.globalExecutor = new ThreadPoolExecutor(
            2, 8,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(GLOBAL_QUEUE_CAPACITY),
            createThreadFactory("syncbus-global"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        this.globalQueue = globalExecutor.getQueue();

        // Scheduler for retries and dedup cleanup (created but not started until start())
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            createThreadFactory("syncbus-scheduler"));
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
            while (state.get() == LifecycleState.RUNNING) {
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

    /**
     * Reserves the dedup slot for one emission.
     *
     * @return the timestamp stored for this emission, or {@code null} when the
     *         message is a duplicate inside the active window
     */
    private Long reserveEmission(Message message) {
        String msgId = message.id().toString();
        long now = System.currentTimeMillis();

        Long previousTime = sentMessageIds.putIfAbsent(msgId, now);
        if (previousTime != null && (now - previousTime) < DEDUP_WINDOW_MS) {
            totalDeduped.incrementAndGet();
            logger.debug("SyncBus: deduplicated message " + msgId);
            return null;
        }
        if (previousTime != null) {
            // Expired dedup entry: refresh the window for this emission.
            sentMessageIds.put(msgId, now);
        }
        return now;
    }

    /** Releases a reservation no sink accepted, so the emission can be retried. */
    private void releaseEmission(Message message, long stamp) {
        sentMessageIds.remove(message.id().toString(), stamp);
        logger.warn("SyncBus: broadcast accepted by no sink; message "
                + message.id() + " was not delivered");
    }

    /**
     * Fans the message out to every registered sink and returns how many
     * sinks actually accepted it.
     *
     * <p>The dedup mark is reserved before the fan-out but released when no
     * sink accepted the message, so a caller can retry a message that was
     * dropped by full queues instead of getting a false success.</p>
     */
    private int deliver(Message message) {
        Long stamp = reserveEmission(message);
        if (stamp == null) {
            return 0;
        }

        totalBroadcast.incrementAndGet();

        // Fan out to all sinks with per-sink isolation.
        int accepted = 0;
        for (SinkContext ctx : sinks.values()) {
            if (ctx.enqueue(message)) {
                accepted++;
            }
        }

        if (accepted == 0) {
            // Never report success for a message no sink accepted; release the
            // dedup mark so the emission can be retried once queues drain.
            // SinkContext.enqueue() already counted each dropped delivery.
            releaseEmission(message, stamp);
        }
        return accepted;
    }

    @Override
    public void register(SyncSink sink) {
        Objects.requireNonNull(sink, "sink");
        String name = sink.name();
        SinkContext created = new SinkContext(sink);
        SinkContext existing = sinks.putIfAbsent(name, created);
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

        // A sink registered after start() must be started immediately;
        // otherwise hot-loading a sync-* package would register a dead sink.
        if (state.get() == LifecycleState.RUNNING) {
            try {
                sink.start();
                created.start();
            } catch (Exception failure) {
                sinks.remove(name, created);
                sinkNamesSnapshot.remove(name);
                created.shutdown();
                try {
                    sink.stop();
                } catch (Exception stopFailure) {
                    failure.addSuppressed(stopFailure);
                }
                throw new IllegalStateException(
                        "Cannot start sink '" + name + "': " + failure.getMessage(), failure);
            }
        }

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

    /**
     * Indexes every {@link SyncSink} published by an active module.
     *
     * <p>Discovery is idempotent by sink name: a sink that is already
     * registered is left untouched, and a sink whose service disappeared is
     * unregistered and stopped. The runtime calls this at bus start and after
     * every module activation or deactivation, so a sink package no longer has
     * to look up the bus when it activates.</p>
     *
     * @param services registry whose {@code SyncSink} services must be indexed
     */
    public void discoverSinks(ServiceRegistry services) {
        Objects.requireNonNull(services, "services");
        Set<String> live = new HashSet<>();
        for (SyncSink sink : services.findAll(SyncSink.class)) {
            String name = sink.name();
            live.add(name);
            if (sinks.containsKey(name)) {
                continue;
            }
            try {
                register(sink);
            } catch (IllegalStateException duplicate) {
                // A concurrent registration won the race; keep the live sink.
                if (!sinks.containsKey(name)) {
                    logger.warn("SyncBus: cannot register discovered sink '" + name
                            + "': " + duplicate.getMessage());
                }
            } catch (RuntimeException failure) {
                logger.warn("SyncBus: cannot register discovered sink '" + name
                        + "': " + failure.getMessage());
            }
        }
        for (String registered : new HashSet<>(sinkNamesSnapshot)) {
            if (!live.contains(registered)) {
                unregister(registered);
            }
        }
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
        LifecycleState currentState = state.get();
        if (currentState != LifecycleState.RUNNING) {
            logger.debug("SyncBus: broadcast called during shutdown or before start");
            return 0;
        }

        if (sinks.isEmpty()) {
            logger.debug("SyncBus: broadcast called but no sinks registered");
            return 0;
        }

        // Fan out in the caller thread so the return value reflects what the
        // sinks actually accepted. Going through the global queue would make
        // the count a guess, because enqueue() can fail later.
        return deliver(message);
    }

    @Override
    public java.util.Map<String, CompletableFuture<Void>> broadcastAsync(Message message) {
        java.util.Map<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();

        if (state.get() != LifecycleState.RUNNING || sinks.isEmpty()) {
            return futures;
        }

        Long stamp = reserveEmission(message);
        if (stamp == null) {
            // The message was already delivered inside the dedup window. No
            // per-sink future is created so a suppressed duplicate cannot be
            // read as a fresh delivery.
            return futures;
        }

        totalBroadcast.incrementAndGet();

        int accepted = 0;
        for (SinkContext ctx : sinks.values()) {
            String sinkName = ctx.sink.name();
            CompletableFuture<Void> sinkFuture = new CompletableFuture<>();
            futures.put(sinkName, sinkFuture);

            if (!ctx.enqueue(message, sinkFuture)) {
                sinkFuture.completeExceptionally(new IllegalStateException(
                        "sink '" + sinkName + "' rejected the message (not running or queue full)"));
                continue;
            }
            accepted++;
            scheduleAsyncTimeout(sinkName, sinkFuture);
        }

        if (accepted == 0) {
            releaseEmission(message, stamp);
        }
        return futures;
    }

    /**
     * Bounds every async future so a stopped or wedged sink cannot retain the
     * caller forever. The timeout is cancelled as soon as the queued task
     * completes, either successfully or with the real delivery failure.
     */
    private void scheduleAsyncTimeout(String sinkName, CompletableFuture<Void> future) {
        ScheduledFuture<?> timeout;
        try {
            timeout = scheduler.schedule(() -> {
                if (future.completeExceptionally(new TimeoutException(
                        "sink '" + sinkName + "' did not process the message within "
                                + asyncTimeoutMillis + "ms"))) {
                    logger.warn("SyncBus: sink '" + sinkName
                            + "' did not process an async message within "
                            + asyncTimeoutMillis + "ms");
                }
            }, asyncTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            future.completeExceptionally(new IllegalStateException(
                    "SyncBus scheduler is not running", stopped));
            return;
        }
        future.whenComplete((ignored, error) -> timeout.cancel(false));
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
        if (!state.compareAndSet(LifecycleState.NEW, LifecycleState.RUNNING)) {
            if (state.get() == LifecycleState.RUNNING) {
                logger.debug("SyncBus: already started");
                return;
            }
            throw new IllegalStateException("Cannot start SyncBus from state: " + state.get());
        }

        // Start global processor
        startGlobalProcessor();

        // Start deduplication cleanup task
        this.dedupCleanupTask = scheduler.scheduleAtFixedRate(
            this::cleanupDedupCache,
            DEDUP_WINDOW_MS, DEDUP_WINDOW_MS, TimeUnit.MILLISECONDS);

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
            state.set(LifecycleState.NEW); // Reset state so future start() calls can retry
            throw e;
        }
    }

    @Override
    public void stop() {
        if (!state.compareAndSet(LifecycleState.RUNNING, LifecycleState.STOPPING)) {
            if (state.get() == LifecycleState.STOPPED) {
                logger.debug("SyncBus: already stopped");
                return;
            }
            // If in NEW state, we still need to clean up resources created in constructor
            if (state.get() == LifecycleState.NEW) {
                cleanupResources();
                state.set(LifecycleState.STOPPED);
                return;
            }
            logger.debug("SyncBus: cannot stop from state: " + state.get());
            return;
        }

        // Stop all sinks first
        for (SinkContext ctx : sinks.values()) {
            try {
                ctx.shutdown();
                ctx.sink.stop();
                logger.info("SyncBus: stopped sink '" + ctx.sink.name() + "'");
            } catch (Exception e) {
                logger.warn("SyncBus: error stopping sink '" + ctx.sink.name() + "': " + e.getMessage());
            }
        }

        cleanupResources();
        state.set(LifecycleState.STOPPED);
    }

    private void cleanupResources() {
        // Wake the global worker if it is blocked in take(); stop() has already
        // moved the state out of RUNNING, so it exits after this sentinel. In
        // NEW state there is no worker, and a queued sentinel would only make
        // shutdown wait for the termination timeout.
        if (state.get() != LifecycleState.NEW) {
            globalQueue.offer(() -> {});
        }
        globalExecutor.shutdown();
        try {
            if (!globalExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                globalExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            globalExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Stop scheduler and dedup cleanup
        if (dedupCleanupTask != null) {
            dedupCleanupTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public boolean isEmpty() {
        return sinks.isEmpty();
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
        final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();

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
            return enqueue(message, null);
        }

        boolean enqueue(Message message, CompletableFuture<Void> completion) {
            if (!running.get() || state.get() != LifecycleState.RUNNING) {
                return false;
            }
            if (completion != null) {
                // Register before offering so a worker that picks the task up
                // immediately cannot leave a completed future in the set.
                pending.add(completion);
            }
            Runnable task = () -> deliverWithRetry(message, 0, completion);
            boolean offered = queue.offer(task);
            if (!offered) {
                if (completion != null) {
                    pending.remove(completion);
                }
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
            IllegalStateException stopped = new IllegalStateException(
                    "sink '" + sink.name() + "' stopped before processing the message");
            for (CompletableFuture<Void> completion : pending) {
                completion.completeExceptionally(stopped);
            }
            pending.clear();
        }

        private void processQueue() {
            while (running.get() && state.get() == LifecycleState.RUNNING) {
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

        private void completeTask(CompletableFuture<Void> completion) {
            if (completion != null) {
                pending.remove(completion);
                completion.complete(null);
            }
        }

        private void failTask(CompletableFuture<Void> completion, Throwable failure) {
            if (completion != null) {
                pending.remove(completion);
                completion.completeExceptionally(failure);
            }
        }

        private void deliverWithRetry(Message message, int attempt,
                                      CompletableFuture<Void> completion) {
            try {
                sink.send(message);
                completeTask(completion);
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
                    try {
                        scheduler.schedule(() -> {
                            if (!running.get() || state.get() != LifecycleState.RUNNING) {
                                failTask(completion, new IllegalStateException(
                                        "sink '" + sink.name() + "' stopped before the retry", e));
                                return;
                            }
                            boolean queued = queue.offer(
                                    () -> deliverWithRetry(message, nextAttempt, completion));
                            if (!queued) {
                                totalDropped.incrementAndGet();
                                failTask(completion, new IllegalStateException(
                                        "sink '" + sink.name() + "' queue full during retry", e));
                            }
                        }, delay, TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException stopped) {
                        failTask(completion, new IllegalStateException(
                                "SyncBus scheduler stopped before the retry", stopped));
                    }
                } else {
                    totalDropped.incrementAndGet();
                    logger.error("SyncBus: sink '" + sink.name() + "' failed after " + MAX_RETRIES + " retries: " + e.getMessage(), e);
                    failTask(completion, e);
                }
            }
        }
    }
}

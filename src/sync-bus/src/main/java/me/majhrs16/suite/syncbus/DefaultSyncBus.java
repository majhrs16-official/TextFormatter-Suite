package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.iflow.TlvCodec;
import me.majhrs16.suite.api.pipeline.DeliveryEnvelope;
import me.majhrs16.suite.api.pipeline.ParallelMode;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.ActionAudit;
import me.majhrs16.suite.api.sync.ActionAuditRecord;
import me.majhrs16.suite.api.sync.HostActionContext;
import me.majhrs16.suite.api.sync.HostActionHandler;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.ProgramGrant;
import me.majhrs16.suite.api.sync.ProgramGrantStore;
import me.majhrs16.suite.api.sync.SyncActionSpec;
import me.majhrs16.suite.api.sync.SyncBridgeCodec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncParamSpec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.api.sync.SyncValueType;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
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
import java.util.function.Function;
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
    private static final int DEFAULT_PER_SINK_QUEUE_CAPACITY = 5000;
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 100;
    private static final long MAX_RETRY_DELAY_MS = 30_000;
    private static final long DEDUP_WINDOW_MS = 60_000;
    private static final int DEDUP_MAX_ENTRIES = 50000;
    private static final long DEFAULT_ASYNC_TIMEOUT_MS = 30_000;
    private static final long CALL_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final String CAPABILITY_SYNC = "sync.";
    private static final String CAPABILITY_MODULE = "module.";
    private static final String REDACTED_VALUE = "<redacted>";
    private static final String OPAQUE_VALUE = "<opaque>";

    // Lifecycle states
    private enum LifecycleState { NEW, RUNNING, STOPPING, STOPPED }

    private final Map<String, SinkContext> sinks = new ConcurrentHashMap<>();
    private final Set<String> sinkNamesSnapshot = new CopyOnWriteArraySet<>();
    private volatile Map<String, HostActionHandler> hostHandlers = Map.of();
    private volatile SyncListener inboundListener;
    private final PluginLogger logger;
    private final long asyncTimeoutMillis;
    private final int perSinkQueueCapacity;
    private final Supplier<ProgramGrantStore> grants;
    private final Supplier<ActionAudit> audit;
    private final ConcurrentMap<String, CallWindow> callWindows = new ConcurrentHashMap<>();

    // Global bounded queue used by the metrics contract and by the shutdown sentinel.
    private final ThreadPoolExecutor globalExecutor;
    private final BlockingQueue<Runnable> globalQueue;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService invocationExecutor;

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
        this(logger, DEFAULT_ASYNC_TIMEOUT_MS, DEFAULT_PER_SINK_QUEUE_CAPACITY,
                () -> null, () -> null);
    }

    /**
     * Builds a bus with the operator grant store and audit trail resolved on
     * every call, so a module can activate before or after the store exists.
     *
     * @param logger diagnostic sink
     * @param grants program grant store supplier; {@code null} result denies
     *               every program
     * @param audit  audit trail supplier; {@code null} result denies every
     *               program action and skips official audit
     */
    public DefaultSyncBus(PluginLogger logger, Supplier<ProgramGrantStore> grants,
                          Supplier<ActionAudit> audit) {
        this(logger, DEFAULT_ASYNC_TIMEOUT_MS, DEFAULT_PER_SINK_QUEUE_CAPACITY,
                grants, audit);
    }

    /**
     * Builds a bus with an explicit action completion bound.
     *
     * <p>The effective timeout of a message delivery is the smaller of the
     * action's declared {@code timeoutMillis} and this bound. Package-private
     * so tests can exercise the timeout without waiting the production
     * bound.</p>
     */
    DefaultSyncBus(PluginLogger logger, long asyncTimeoutMillis) {
        this(logger, asyncTimeoutMillis, DEFAULT_PER_SINK_QUEUE_CAPACITY,
                () -> null, () -> null);
    }

    /**
     * Builds a bus with explicit bounds. Package-private for tests that need
     * to fill a sink queue without allocating the production capacity.
     */
    DefaultSyncBus(PluginLogger logger, long asyncTimeoutMillis, int perSinkQueueCapacity) {
        this(logger, asyncTimeoutMillis, perSinkQueueCapacity, () -> null, () -> null);
    }

    DefaultSyncBus(PluginLogger logger, long asyncTimeoutMillis, int perSinkQueueCapacity,
                   Supplier<ProgramGrantStore> grants, Supplier<ActionAudit> audit) {
        this.logger = Objects.requireNonNull(logger, "logger");
        if (asyncTimeoutMillis <= 0) {
            throw new IllegalArgumentException("asyncTimeoutMillis must be positive");
        }
        if (perSinkQueueCapacity <= 0) {
            throw new IllegalArgumentException("perSinkQueueCapacity must be positive");
        }
        this.asyncTimeoutMillis = asyncTimeoutMillis;
        this.perSinkQueueCapacity = perSinkQueueCapacity;
        this.grants = grants == null ? () -> null : grants;
        this.audit = audit == null ? () -> null : audit;

        // Global executor with CallerRunsPolicy for backpressure
        this.globalExecutor = new ThreadPoolExecutor(
            2, 8,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(GLOBAL_QUEUE_CAPACITY),
            createThreadFactory("syncbus-global"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        this.globalQueue = globalExecutor.getQueue();

        // Scheduler for retries, action timeouts and dedup cleanup (created but
        // not started until start()).
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            createThreadFactory("syncbus-scheduler"));

        // Native actions run off the caller thread so the bus can bound them
        // with the action timeout and map a crash to HostFailure.
        this.invocationExecutor = Executors.newCachedThreadPool(
            createThreadFactory("syncbus-invoke"));
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

    @Override
    public void register(SyncSink sink) {
        Objects.requireNonNull(sink, "sink");
        String name = sink.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("sink name must not be blank");
        }

        SyncSurface surface = Objects.requireNonNull(sink.surface(), "surface");
        if (!name.equals(surface.backend())) {
            throw new IllegalArgumentException(
                    "sink '" + name + "' publishes backend '" + surface.backend()
                            + "'; the backend namespace must match the sink name");
        }
        if (!surface.hasLeastCommonMultiple()) {
            throw new IllegalArgumentException(
                    "sink '" + name + "' does not publish the least common multiple");
        }

        SinkContext created = new SinkContext(sink, surface);
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
     * Indexes every {@link SyncSink} and {@link HostActionHandler} published by
     * an active module.
     *
     * <p>Discovery is idempotent by sink name: a sink that is already
     * registered is left untouched, and a sink whose service disappeared is
     * unregistered and stopped. Host action handlers are reindexed by their
     * canonical name on every pass, so a handler that disappears stops being
     * reachable. The runtime calls this at bus start and after every module
     * activation or deactivation, so a sink or handler package no longer has
     * to look up the bus when it activates.</p>
     *
     * @param services registry whose services must be indexed
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
        indexHostHandlers(services);
    }

    /**
     * Rebuilds the host action index. A duplicate canonical name keeps the
     * first handler and is reported, so an ambiguous action is never routed by
     * registration order.
     */
    private void indexHostHandlers(ServiceRegistry services) {
        Map<String, HostActionHandler> discovered = new LinkedHashMap<>();
        for (HostActionHandler handler : services.findAll(HostActionHandler.class)) {
            String name;
            try {
                name = handler.canonicalName();
            } catch (RuntimeException invalid) {
                logger.warn("SyncBus: invalid host action handler: "
                        + invalid.getMessage());
                continue;
            }
            HostActionHandler previous = discovered.putIfAbsent(name, handler);
            if (previous != null) {
                logger.warn("SyncBus: duplicate host action handler for '" + name
                        + "'; keeping the first");
            }
        }
        hostHandlers = Map.copyOf(discovered);
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
    public Set<SyncSurface> surfaces() {
        Set<SyncSurface> published = new LinkedHashSet<>();
        for (SinkContext ctx : orderedContexts()) {
            published.add(ctx.surface);
        }
        return Collections.unmodifiableSet(published);
    }

    @Override
    public SyncResult invoke(SyncCall call) {
        Objects.requireNonNull(call, "call");
        long startedNanos = System.nanoTime();
        if (state.get() != LifecycleState.RUNNING) {
            return new SyncResult.HostFailure("sync bus is not running");
        }

        SinkContext ctx = sinks.get(call.backend());
        if (ctx == null) {
            return recordAudit(call, null, new SyncResult.Unsupported(
                    "backend '" + call.backend() + "' is not registered"), startedNanos);
        }

        SyncActionSpec spec = ctx.surface.action(call.action()).orElse(null);
        if (spec == null) {
            return recordAudit(call, null, new SyncResult.Unsupported(
                    "action '" + call.canonicalName() + "' is not published"), startedNanos);
        }

        SyncResult denied = authorize(call, spec);
        if (denied != null) {
            return recordAudit(call, spec, denied, startedNanos);
        }

        SyncResult invalid = validateArguments(call, spec);
        if (invalid != null) {
            return recordAudit(call, spec, invalid, startedNanos);
        }

        SyncResult result;
        if (SyncSurface.MESSAGE_SEND.equals(call.action())
                || SyncSurface.MESSAGE_DELIVER.equals(call.action())) {
            // The message paths stay in the per-sink queue so directed delivery
            // keeps the same isolation and retry policy as a fan-out.
            result = await(dispatchCall(ctx, call, spec));
        } else {
            result = executeBounded(ctx.sink, call, effectiveTimeout(spec));
        }
        return recordAudit(call, spec, result, startedNanos);
    }

    @Override
    public SyncResult invokeHost(SyncCall call, HostActionContext context) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(context, "context");
        long startedNanos = System.nanoTime();
        if (state.get() != LifecycleState.RUNNING) {
            return new SyncResult.HostFailure("sync bus is not running");
        }

        HostActionHandler handler = hostHandlers.get(call.canonicalName());
        if (handler == null) {
            return recordAudit(call, null, new SyncResult.Unsupported(
                    "host action '" + call.canonicalName() + "' is not registered"),
                    startedNanos);
        }
        if (!handler.handles(call)) {
            return recordAudit(call, null, new SyncResult.Unsupported(
                    "host action '" + call.canonicalName()
                            + "' does not match its registered handler"), startedNanos);
        }

        SyncActionSpec spec = handler.action();
        SyncResult denied = authorizeHost(call, spec);
        if (denied != null) {
            return recordAudit(call, spec, denied, startedNanos);
        }

        SyncResult invalid = validateArguments(call, spec);
        if (invalid != null) {
            return recordAudit(call, spec, invalid, startedNanos);
        }

        SyncResult result = executeBounded(
                () -> handler.invoke(call, context), call, effectiveTimeout(spec));
        return recordAudit(call, spec, result, startedNanos);
    }

    @Override
    public List<SyncResult> broadcast(SyncCall call) {
        Objects.requireNonNull(call, "call");
        List<SinkContext> targets = orderedContexts();
        if (targets.isEmpty()) {
            return List.of();
        }

        if (!isLcmAction(call.action())) {
            List<SyncResult> results = new ArrayList<>(targets.size());
            for (SinkContext ctx : targets) {
                results.add(new SyncResult.Unsupported(
                        "broadcast only fans out the LCM; '" + call.canonicalName()
                                + "' is not LCM"));
            }
            return List.copyOf(results);
        }

        List<SyncResult> results = new ArrayList<>(targets.size());
        for (SinkContext ctx : targets) {
            results.add(invoke(copyForBackend(call, ctx.surface.backend())));
        }
        return List.copyOf(results);
    }

    @Override
    public List<SyncResult> send(Message message) {
        Objects.requireNonNull(message, "message");
        List<SinkContext> targets = orderedContexts();
        if (targets.isEmpty()) {
            return List.of();
        }
        if (state.get() != LifecycleState.RUNNING) {
            List<SyncResult> stopped = new ArrayList<>(targets.size());
            for (SinkContext ctx : targets) {
                stopped.add(new SyncResult.HostFailure(
                        "sync bus is not running; backend '" + ctx.surface.backend()
                                + "' did not receive the message"));
            }
            return List.copyOf(stopped);
        }

        Long stamp = reserveEmission(message);
        if (stamp == null) {
            // No delivery was attempted for this duplicate, so there is no
            // per-backend result to report.
            return List.of();
        }
        totalBroadcast.incrementAndGet();

        List<CompletableFuture<SyncResult>> outcomes = new ArrayList<>(targets.size());
        for (SinkContext ctx : targets) {
            outcomes.add(dispatchCall(ctx, messageSendCall(ctx.surface.backend(), message),
                    sendSpec(ctx)));
        }

        List<SyncResult> results = new ArrayList<>(outcomes.size());
        boolean anyAccepted = false;
        for (CompletableFuture<SyncResult> outcome : outcomes) {
            SyncResult result = await(outcome);
            results.add(result);
            anyAccepted |= result.isOk();
        }
        if (!anyAccepted) {
            releaseEmission(message, stamp);
        }
        return List.copyOf(results);
    }

    @Override
    public List<SyncResult> sendTo(String backend, Message message) {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(message, "message");
        return sendTo(List.of(backend), message, ParallelMode.SEQUENTIAL);
    }

    @Override
    public List<SyncResult> sendTo(List<String> backends, Message message,
                                   ParallelMode mode) {
        Objects.requireNonNull(backends, "backends");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(mode, "mode");
        if (backends.isEmpty()) {
            return List.of();
        }

        return dispatchDirected(backends, mode,
                backend -> messageSendCall(backend, message));
    }

    @Override
    public List<SyncResult> deliver(DeliveryEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        List<SinkContext> targets = orderedContexts();
        if (targets.isEmpty()) {
            return List.of();
        }
        Message message = envelope.message();
        if (state.get() != LifecycleState.RUNNING) {
            List<SyncResult> stopped = new ArrayList<>(targets.size());
            for (SinkContext ctx : targets) {
                stopped.add(new SyncResult.HostFailure(
                        "sync bus is not running; backend '" + ctx.surface.backend()
                                + "' did not receive the envelope"));
            }
            return List.copyOf(stopped);
        }

        Long stamp = reserveEmission(message);
        if (stamp == null) {
            // No delivery was attempted for this duplicate, so there is no
            // per-backend result to report.
            return List.of();
        }
        totalBroadcast.incrementAndGet();

        List<CompletableFuture<SyncResult>> outcomes = new ArrayList<>(targets.size());
        for (SinkContext ctx : targets) {
            outcomes.add(dispatchCall(ctx,
                    messageDeliverCall(ctx.surface.backend(), envelope),
                    deliverSpec(ctx)));
        }

        List<SyncResult> results = new ArrayList<>(outcomes.size());
        boolean anyAccepted = false;
        for (CompletableFuture<SyncResult> outcome : outcomes) {
            SyncResult result = await(outcome);
            results.add(result);
            anyAccepted |= result.isOk();
        }
        if (!anyAccepted) {
            releaseEmission(message, stamp);
        }
        return List.copyOf(results);
    }

    @Override
    public List<SyncResult> deliverTo(List<String> backends, DeliveryEnvelope envelope,
                                      ParallelMode mode) {
        Objects.requireNonNull(backends, "backends");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(mode, "mode");
        if (backends.isEmpty()) {
            return List.of();
        }

        return dispatchDirected(backends, mode,
                backend -> messageDeliverCall(backend, envelope));
    }

    /**
     * Dispatches one call per requested backend, preserving order. Missing
     * backends and a stopped bus complete immediately with an explicit result
     * instead of throwing, so the caller keeps one result per requested target.
     */
    private List<SyncResult> dispatchDirected(List<String> backends,
                                              ParallelMode mode,
                                              Function<String, SyncCall> calls) {
        boolean parallel = mode != ParallelMode.SEQUENTIAL;
        List<CompletableFuture<SyncResult>> pending = new ArrayList<>(backends.size());
        List<SyncResult> results = new ArrayList<>(backends.size());
        for (String backend : backends) {
            Objects.requireNonNull(backend, "backend");
            CompletableFuture<SyncResult> outcome = dispatchTo(backend, calls.apply(backend));
            if (parallel) {
                pending.add(outcome);
            } else {
                results.add(await(outcome));
            }
        }
        if (parallel) {
            for (CompletableFuture<SyncResult> outcome : pending) {
                results.add(await(outcome));
            }
        }
        return List.copyOf(results);
    }

    private CompletableFuture<SyncResult> dispatchTo(String backend, SyncCall call) {
        SinkContext ctx = sinks.get(backend);
        if (ctx == null) {
            logger.warn("SyncBus: backend '" + backend + "' not found for directed '"
                    + call.action() + "'");
            return CompletableFuture.completedFuture(new SyncResult.Unsupported(
                    "backend '" + backend + "' is not registered"));
        }
        if (state.get() != LifecycleState.RUNNING) {
            return CompletableFuture.completedFuture(new SyncResult.HostFailure(
                    "sync bus is not running; backend '" + backend
                            + "' did not receive the action"));
        }
        SyncActionSpec spec = ctx.surface.action(call.action()).orElse(null);
        if (spec == null) {
            return CompletableFuture.completedFuture(new SyncResult.Unsupported(
                    "action '" + call.canonicalName() + "' is not published"));
        }
        return dispatchCall(ctx, call, spec);
    }

    /** @return contexts in registration order, so results align with surfaces() */
    private List<SinkContext> orderedContexts() {
        List<SinkContext> ordered = new ArrayList<>(sinkNamesSnapshot.size());
        for (String name : sinkNamesSnapshot) {
            SinkContext ctx = sinks.get(name);
            if (ctx != null) {
                ordered.add(ctx);
            }
        }
        return ordered;
    }

    private static SyncCall copyForBackend(SyncCall call, String backend) {
        return new SyncCall(backend, call.action(), call.args(), call.principal(),
                call.idempotencyKey(), call.reason());
    }

    private static SyncCall messageSendCall(String backend, Message message) {
        return new SyncCall(
                backend,
                SyncSurface.MESSAGE_SEND,
                Map.of("message", message),
                new Principal.Official("suite.pipeline"),
                message.id() + "|" + SyncSurface.MESSAGE_SEND + "|" + backend,
                "pipeline delivery");
    }

    private static SyncCall messageDeliverCall(String backend, DeliveryEnvelope envelope) {
        return new SyncCall(
                backend,
                SyncSurface.MESSAGE_DELIVER,
                Map.of("delivery", envelope),
                new Principal.Official("suite.pipeline"),
                envelope.message().id() + "|" + SyncSurface.MESSAGE_DELIVER + "|" + backend,
                "pipeline delivery");
    }

    private static SyncActionSpec sendSpec(SinkContext ctx) {
        return ctx.surface.action(SyncSurface.MESSAGE_SEND)
                .orElseThrow(() -> new IllegalStateException(
                        "backend '" + ctx.surface.backend() + "' does not publish "
                                + SyncSurface.MESSAGE_SEND));
    }

    private static SyncActionSpec deliverSpec(SinkContext ctx) {
        return ctx.surface.action(SyncSurface.MESSAGE_DELIVER)
                .orElseThrow(() -> new IllegalStateException(
                        "backend '" + ctx.surface.backend() + "' does not publish "
                                + SyncSurface.MESSAGE_DELIVER));
    }

    private long effectiveTimeout(SyncActionSpec spec) {
        return Math.min(spec.timeoutMillis(), asyncTimeoutMillis);
    }

    /**
     * Queues one call on the sink worker and bounds it with an explicit timer.
     * The returned future always completes: with the real result, with a
     * rejection, or with {@link SyncResult.Timeout}.
     */
    private CompletableFuture<SyncResult> dispatchCall(SinkContext ctx, SyncCall call,
                                                       SyncActionSpec spec) {
        CompletableFuture<SyncResult> outcome = new CompletableFuture<>();
        if (!ctx.enqueue(call, outcome)) {
            outcome.complete(new SyncResult.HostFailure(
                    "backend '" + ctx.surface.backend()
                            + "' rejected the action (not running or queue full)"));
            return outcome;
        }

        long timeout = effectiveTimeout(spec);
        ScheduledFuture<?> timer;
        try {
            timer = scheduler.schedule(() -> {
                if (outcome.complete(new SyncResult.Timeout(
                        "backend '" + ctx.surface.backend() + "' did not finish '"
                                + call.action() + "' within " + timeout + "ms"))) {
                    logger.warn("SyncBus: backend '" + ctx.surface.backend()
                            + "' timed out after " + timeout + "ms on action '"
                            + call.action() + "'");
                }
            }, timeout, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            outcome.complete(new SyncResult.HostFailure(
                    "sync bus scheduler is not running; action '" + call.action()
                            + "' was not executed"));
            return outcome;
        }
        ScheduledFuture<?> scheduled = timer;
        outcome.whenComplete((ignored, failure) -> scheduled.cancel(false));
        return outcome;
    }

    private SyncResult executeBounded(SyncSink sink, SyncCall call, long timeoutMillis) {
        return executeBounded(() -> sink.invoke(call), call, timeoutMillis);
    }

    private SyncResult executeBounded(Supplier<SyncResult> action, SyncCall call,
                                      long timeoutMillis) {
        CompletableFuture<SyncResult> outcome;
        try {
            outcome = CompletableFuture.supplyAsync(action, invocationExecutor);
        } catch (RejectedExecutionException stopped) {
            return new SyncResult.HostFailure(
                    "sync bus invocation executor is not running");
        }

        try {
            SyncResult result = outcome.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return result != null
                    ? result
                    : new SyncResult.HostFailure("action returned no result");
        } catch (TimeoutException timeout) {
            outcome.cancel(true);
            return new SyncResult.Timeout(
                    "action '" + call.canonicalName() + "' exceeded "
                            + timeoutMillis + "ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outcome.cancel(true);
            return new SyncResult.HostFailure(
                    "interrupted while executing '" + call.canonicalName() + "'");
        } catch (ExecutionException failure) {
            return new SyncResult.HostFailure(describe(failure.getCause()));
        }
    }

    private static SyncResult await(CompletableFuture<SyncResult> outcome) {
        try {
            SyncResult result = outcome.get();
            return result != null
                    ? result
                    : new SyncResult.HostFailure("sink returned no result");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new SyncResult.HostFailure("interrupted while waiting for the action");
        } catch (ExecutionException failure) {
            return new SyncResult.HostFailure(describe(failure.getCause()));
        }
    }

    /**
     * Authorizes one sink action. Official callers may only invoke the least
     * common multiple; a third-party program needs an operator grant that
     * covers the derived {@code sync.<backend>.<action>} capability.
     *
     * @return {@code null} when the action may run, otherwise the explicit
     *         denial or rate limit
     */
    private SyncResult authorize(SyncCall call, SyncActionSpec spec) {
        if (call.principal() instanceof Principal.Official) {
            return isLcmAction(spec.id())
                    ? null
                    : new SyncResult.Denied(
                            "Principal.Official may only invoke the least common multiple");
        }
        return authorizeProgram(call, spec, CAPABILITY_SYNC);
    }

    /**
     * Authorizes one host action. Host actions are never part of the least
     * common multiple, so an official caller is always denied. A program needs
     * a grant covering {@code module.<backend>.<action>}.
     *
     * @return {@code null} when the action may run, otherwise the explicit
     *         denial or rate limit
     */
    private SyncResult authorizeHost(SyncCall call, SyncActionSpec spec) {
        if (call.principal() instanceof Principal.Official) {
            return new SyncResult.Denied(
                    "Principal.Official may not invoke host actions");
        }
        return authorizeProgram(call, spec, CAPABILITY_MODULE);
    }

    private SyncResult authorizeProgram(SyncCall call, SyncActionSpec spec, String prefix) {
        if (!(call.principal() instanceof Principal.Program program)) {
            return new SyncResult.Denied(
                    "unknown principal " + principalId(call.principal()) + " is denied");
        }

        ProgramGrantStore store;
        try {
            store = grants.get();
        } catch (RuntimeException failure) {
            return new SyncResult.Denied(
                    "program grant store failed for " + principalId(program)
                            + "; the call is denied: " + describe(failure));
        }
        if (store == null) {
            return new SyncResult.Denied(
                    "program grant store is not available; " + principalId(program)
                            + " is denied");
        }

        ProgramGrant grant;
        try {
            grant = store.find(program.sha256()).orElse(null);
        } catch (RuntimeException failure) {
            return new SyncResult.Denied(
                    "program grant store failed for " + principalId(program)
                            + "; the call is denied: " + describe(failure));
        }
        if (grant == null) {
            return new SyncResult.Denied(
                    "no operator grant for " + principalId(program));
        }

        String capability = prefix + call.canonicalName();
        if (!grant.allows(capability)) {
            return new SyncResult.Denied(
                    "operator grant for " + principalId(program)
                            + " does not authorize capability '" + capability + "'");
        }
        if (!grant.permits(spec.kind())) {
            return new SyncResult.Denied(
                    "capability '" + capability + "' is " + spec.kind()
                            + " and the grant does not set allow-destructive: true");
        }

        int payloadBytes;
        try {
            payloadBytes = TlvCodec.encodeMap(redactedArguments(call, spec)).length;
        } catch (RuntimeException failure) {
            return new SyncResult.Denied(
                    "arguments of '" + call.canonicalName()
                            + "' have no neutral representation; the call is denied: "
                            + describe(failure));
        }
        if (payloadBytes > grant.maxPayloadBytes()) {
            return new SyncResult.Denied(
                    "argument payload of '" + call.canonicalName() + "' is "
                            + payloadBytes + " bytes and exceeds max-payload-bytes "
                            + grant.maxPayloadBytes() + " for " + principalId(program));
        }

        ActionAudit trail;
        try {
            trail = audit.get();
        } catch (RuntimeException failure) {
            return new SyncResult.Denied(
                    "audit trail failed for " + principalId(program)
                            + "; the call is denied: " + describe(failure));
        }
        if (trail == null) {
            return new SyncResult.Denied(
                    "audit trail is not available; " + principalId(program)
                            + " cannot execute audited actions");
        }
        try {
            trail.ensureWritable();
        } catch (RuntimeException failure) {
            return new SyncResult.Denied(
                    "audit trail is not writable; " + principalId(program)
                            + " cannot execute audited actions: " + describe(failure));
        }

        long retryAfter = callWindow(program.sha256()).reserve(grant.callsPerMinute());
        return retryAfter > 0 ? new SyncResult.RateLimited(retryAfter) : null;
    }

    private CallWindow callWindow(String sha256) {
        return callWindows.computeIfAbsent(sha256, ignored -> new CallWindow());
    }

    /**
     * Appends the audit entry for one attempt and returns the same result.
     *
     * <p>A program action is denied before it runs when the trail is missing or
     * not writable, so this method only logs a post-execution append failure:
     * the native effect cannot be reverted (ADR-036 §16.6.8). Official callers
     * are audited best-effort so a full disk never stops the pipeline.</p>
     */
    private SyncResult recordAudit(SyncCall call, SyncActionSpec spec, SyncResult result,
                                   long startedNanos) {
        ActionAudit trail;
        try {
            trail = audit.get();
        } catch (RuntimeException failure) {
            logger.error("SyncBus: cannot resolve the audit trail for '"
                    + call.canonicalName() + "': " + describe(failure));
            return result;
        }
        if (trail == null) {
            if (call.principal() instanceof Principal.Program) {
                logger.error("SyncBus: program action '" + call.canonicalName()
                        + "' finished without an audit trail");
            }
            return result;
        }

        String argsHash;
        try {
            argsHash = sha256(TlvCodec.encodeMap(redactedArguments(call, spec)));
        } catch (RuntimeException failure) {
            argsHash = ActionAuditRecord.NO_ARGS;
        }
        ActionAuditRecord record = new ActionAuditRecord(
                Instant.now(),
                call.principal() instanceof Principal.Program program
                        ? program.sha256() : ActionAuditRecord.NO_PROGRAM,
                principalId(call.principal()),
                call.backend(),
                call.action(),
                argsHash,
                SyncBridgeCodec.statusName(result),
                Math.max(0, TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedNanos)),
                auditDetail(result));
        try {
            trail.record(record);
        } catch (RuntimeException failure) {
            logger.error("SyncBus: cannot append the audit entry for '"
                    + call.canonicalName() + "': " + describe(failure));
        }
        return result;
    }

    /**
     * Builds the argument map used for the audit digest and the payload bound.
     *
     * <p>Secret parameters, parameters not declared by the action and values
     * without a neutral representation are replaced by a fixed marker before
     * hashing: the trail never carries argument values and an undeclared value
     * is treated as secret (fail-closed). Keys are sorted so the digest is
     * deterministic.</p>
     */
    private static Map<String, TlvCodec.TlvValue> redactedArguments(
            SyncCall call, SyncActionSpec spec) {
        Map<String, SyncParamSpec> declared = new HashMap<>();
        if (spec != null) {
            for (SyncParamSpec param : spec.params()) {
                declared.put(param.name(), param);
            }
        }
        Map<String, TlvCodec.TlvValue> encoded = new TreeMap<>();
        for (Map.Entry<String, Object> arg : call.args().entrySet()) {
            SyncParamSpec param = declared.get(arg.getKey());
            if (param == null || param.secret()) {
                encoded.put(arg.getKey(), TlvCodec.stringValue(REDACTED_VALUE));
                continue;
            }
            try {
                encoded.put(arg.getKey(), SyncBridgeCodec.encodeValue(arg.getValue()));
            } catch (IllegalArgumentException opaque) {
                encoded.put(arg.getKey(), TlvCodec.stringValue(OPAQUE_VALUE));
            }
        }
        return encoded;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static String auditDetail(SyncResult result) {
        if (result instanceof SyncResult.Ok) {
            return "";
        }
        if (result instanceof SyncResult.Denied denied) {
            return denied.reason();
        }
        if (result instanceof SyncResult.Unsupported unsupported) {
            return unsupported.reason();
        }
        if (result instanceof SyncResult.InvalidArgument invalid) {
            return invalid.reason();
        }
        if (result instanceof SyncResult.NotFound notFound) {
            return notFound.reason();
        }
        if (result instanceof SyncResult.Conflict conflict) {
            return conflict.reason();
        }
        if (result instanceof SyncResult.RateLimited limited) {
            return "retry after " + limited.retryAfterMillis() + "ms";
        }
        if (result instanceof SyncResult.Timeout timeout) {
            return timeout.reason();
        }
        if (result instanceof SyncResult.HostFailure failure) {
            return failure.reason();
        }
        return "";
    }

    /**
     * Rolling one-minute call budget for one program image.
     *
     * <p>The limit is read from the current grant on every reservation, so a
     * reload that changes {@code calls-per-minute} applies without restarting
     * the bus.</p>
     */
    private static final class CallWindow {
        private long windowStartNanos = System.nanoTime();
        private int used;

        synchronized long reserve(int limit) {
            long now = System.nanoTime();
            long elapsed = now - windowStartNanos;
            if (elapsed < 0 || elapsed >= CALL_WINDOW_NANOS) {
                windowStartNanos = now;
                elapsed = 0;
                used = 0;
            }
            if (used >= limit) {
                long remaining = CALL_WINDOW_NANOS - elapsed;
                return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
            }
            used++;
            return 0;
        }
    }

    private static String principalId(Principal principal) {
        if (principal instanceof Principal.Official official) {
            return "module:" + official.moduleId();
        }
        if (principal instanceof Principal.Program program) {
            return "program:" + program.sha256();
        }
        return principal.toString();
    }

    private static boolean isLcmAction(String action) {
        return SyncSurface.BACKEND_INFO.equals(action)
                || SyncSurface.MESSAGE_SEND.equals(action)
                || SyncSurface.MESSAGE_DELIVER.equals(action);
    }

    private static SyncResult validateArguments(SyncCall call, SyncActionSpec spec) {
        for (SyncParamSpec param : spec.params()) {
            Object value = call.args().get(param.name());
            if (value == null) {
                if (param.required()) {
                    return new SyncResult.InvalidArgument(
                            "missing required argument '" + param.name() + "'");
                }
                continue;
            }
            String problem = validateValue(param.name(), param, value);
            if (problem != null) {
                return new SyncResult.InvalidArgument(problem);
            }
        }
        for (String name : call.args().keySet()) {
            if (spec.params().stream().noneMatch(param -> param.name().equals(name))) {
                return new SyncResult.InvalidArgument(
                        "unknown argument '" + name + "' for action '" + spec.id() + "'");
            }
        }
        return null;
    }

    private static String validateValue(String name, SyncParamSpec param, Object value) {
        if (param.item() != null) {
            if (!(value instanceof Iterable<?> items)) {
                return "'" + name + "' must be a list";
            }
            int index = 0;
            for (Object item : items) {
                String problem = validateValue(name + "[" + index + "]", param.item(), item);
                if (problem != null) {
                    return problem;
                }
                index++;
            }
            return null;
        }

        return switch (param.type()) {
            case STRING -> value instanceof String
                    ? null : "'" + name + "' must be a string";
            case INTEGER -> isInteger(value)
                    ? null : "'" + name + "' must be an integer";
            case BOOLEAN -> value instanceof Boolean
                    ? null : "'" + name + "' must be a boolean";
            case DURATION -> isDuration(value)
                    ? null : "'" + name + "' must be a non-negative duration";
            case ACTOR_REF, CHANNEL_REF, ROLE_REF -> value instanceof String text
                    && !text.isBlank()
                    ? null : "'" + name + "' must be a non-blank reference";
            case MESSAGE_REF -> value instanceof Message
                    || (value instanceof String text && !text.isBlank())
                    ? null : "'" + name + "' must be a Message or a non-blank message id";
            case DELIVERY_REF -> value instanceof DeliveryEnvelope
                    ? null : "'" + name + "' must be a DeliveryEnvelope";
            case OBJECT -> value instanceof Map<?, ?>
                    ? null : "'" + name + "' must be an object";
        };
    }

    private static boolean isInteger(Object value) {
        return value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger;
    }

    private static boolean isDuration(Object value) {
        if (value instanceof Duration duration) {
            return !duration.isNegative();
        }
        return value instanceof Number number && isInteger(number)
                && number.longValue() >= 0;
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
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

        // Native actions may still be blocked in a backend; interrupting them
        // is the only cancellation available once the bus is stopping.
        invocationExecutor.shutdownNow();
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

    /** @return queued actions for one backend; package-private for tests */
    int queuedActions(String backend) {
        SinkContext ctx = sinks.get(backend);
        return ctx == null ? 0 : ctx.queue.size();
    }

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
        final SyncSurface surface;
        final BlockingQueue<Runnable> queue;
        final ThreadPoolExecutor executor;
        final AtomicBoolean running = new AtomicBoolean(false);
        final Set<CompletableFuture<SyncResult>> pending = ConcurrentHashMap.newKeySet();

        SinkContext(SyncSink sink, SyncSurface surface) {
            this.sink = sink;
            this.surface = surface;
            this.queue = new LinkedBlockingQueue<>(perSinkQueueCapacity);
            this.executor = new ThreadPoolExecutor(
                1, 1,
                60L, TimeUnit.SECONDS,
                queue,
                createThreadFactory("syncbus-sink-" + sink.name()),
                new ThreadPoolExecutor.DiscardPolicy()
            );
        }

        boolean enqueue(SyncCall call, CompletableFuture<SyncResult> completion) {
            if (!running.get() || state.get() != LifecycleState.RUNNING) {
                return false;
            }
            // Register before offering so a worker that picks the task up
            // immediately cannot leave a completed future in the set.
            pending.add(completion);
            Runnable task = () -> deliverWithRetry(call, 0, completion);
            boolean offered = queue.offer(task);
            if (!offered) {
                pending.remove(completion);
                // Sink queue full - drop with metric
                totalDropped.incrementAndGet();
                logger.warn("SyncBus: sink '" + sink.name() + "' queue full, dropping action '"
                        + call.action() + "'");
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
            SyncResult stopped = new SyncResult.HostFailure(
                    "sink '" + sink.name() + "' stopped before processing the action");
            for (CompletableFuture<SyncResult> completion : pending) {
                completion.complete(stopped);
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

        private void completeTask(CompletableFuture<SyncResult> completion,
                                  SyncResult result) {
            if (completion != null) {
                pending.remove(completion);
                completion.complete(result);
            }
        }

        private void failTask(CompletableFuture<SyncResult> completion,
                              SyncResult failure) {
            if (completion != null) {
                pending.remove(completion);
                completion.complete(failure);
            }
        }

        private void deliverWithRetry(SyncCall call, int attempt,
                                      CompletableFuture<SyncResult> completion) {
            if (completion.isDone()) {
                // The caller already received a timeout or shutdown result.
                return;
            }

            SyncResult result;
            try {
                result = sink.invoke(call);
            } catch (Exception failure) {
                result = new SyncResult.HostFailure(describe(failure));
            }
            if (result == null) {
                result = new SyncResult.HostFailure("sink returned no result");
            }
            if (completion.isDone()) {
                return;
            }

            SyncActionSpec spec = surface.action(call.action()).orElse(null);
            boolean retryable = spec != null
                    && spec.idempotent()
                    && result instanceof SyncResult.HostFailure;

            if (retryable && attempt < MAX_RETRIES) {
                totalRetries.incrementAndGet();
                long delay = Math.min(
                    INITIAL_RETRY_DELAY_MS * (1L << attempt),
                    MAX_RETRY_DELAY_MS
                );
                logger.debug("SyncBus: sink '" + sink.name() + "' action '"
                        + call.action() + "' failed (attempt " + (attempt + 1)
                        + "/" + MAX_RETRIES + "), retrying in " + delay + "ms: "
                        + ((SyncResult.HostFailure) result).reason());

                int nextAttempt = attempt + 1;
                try {
                    scheduler.schedule(() -> {
                        if (!running.get() || state.get() != LifecycleState.RUNNING) {
                            failTask(completion, new SyncResult.HostFailure(
                                    "sink '" + sink.name()
                                            + "' stopped before the retry"));
                            return;
                        }
                        boolean queued = queue.offer(
                                () -> deliverWithRetry(call, nextAttempt, completion));
                        if (!queued) {
                            totalDropped.incrementAndGet();
                            failTask(completion, new SyncResult.HostFailure(
                                    "sink '" + sink.name()
                                            + "' queue full during retry"));
                        }
                    }, delay, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException stopped) {
                    failTask(completion, new SyncResult.HostFailure(
                            "SyncBus scheduler stopped before the retry"));
                }
            } else {
                if (result instanceof SyncResult.HostFailure) {
                    totalDropped.incrementAndGet();
                    logger.error("SyncBus: sink '" + sink.name()
                            + "' failed after " + MAX_RETRIES + " retries: "
                            + ((SyncResult.HostFailure) result).reason());
                }
                completeTask(completion, result);
            }
        }
    }
}

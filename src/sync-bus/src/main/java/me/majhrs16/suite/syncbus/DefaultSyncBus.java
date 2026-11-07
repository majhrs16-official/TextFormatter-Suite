package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncActionSpec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncParamSpec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.api.sync.SyncValueType;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    // Lifecycle states
    private enum LifecycleState { NEW, RUNNING, STOPPING, STOPPED }

    private final Map<String, SinkContext> sinks = new ConcurrentHashMap<>();
    private final Set<String> sinkNamesSnapshot = new CopyOnWriteArraySet<>();
    private volatile SyncListener inboundListener;
    private final PluginLogger logger;
    private final long asyncTimeoutMillis;
    private final int perSinkQueueCapacity;

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
        this(logger, DEFAULT_ASYNC_TIMEOUT_MS, DEFAULT_PER_SINK_QUEUE_CAPACITY);
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
        this(logger, asyncTimeoutMillis, DEFAULT_PER_SINK_QUEUE_CAPACITY);
    }

    /**
     * Builds a bus with explicit bounds. Package-private for tests that need
     * to fill a sink queue without allocating the production capacity.
     */
    DefaultSyncBus(PluginLogger logger, long asyncTimeoutMillis, int perSinkQueueCapacity) {
        this.logger = Objects.requireNonNull(logger, "logger");
        if (asyncTimeoutMillis <= 0) {
            throw new IllegalArgumentException("asyncTimeoutMillis must be positive");
        }
        if (perSinkQueueCapacity <= 0) {
            throw new IllegalArgumentException("perSinkQueueCapacity must be positive");
        }
        this.asyncTimeoutMillis = asyncTimeoutMillis;
        this.perSinkQueueCapacity = perSinkQueueCapacity;

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
        if (state.get() != LifecycleState.RUNNING) {
            return new SyncResult.HostFailure("sync bus is not running");
        }

        SinkContext ctx = sinks.get(call.backend());
        if (ctx == null) {
            return new SyncResult.Unsupported(
                    "backend '" + call.backend() + "' is not registered");
        }

        SyncActionSpec spec = ctx.surface.action(call.action()).orElse(null);
        if (spec == null) {
            return new SyncResult.Unsupported(
                    "action '" + call.canonicalName() + "' is not published");
        }

        SyncResult denied = authorize(call, spec);
        if (denied != null) {
            return denied;
        }

        SyncResult invalid = validateArguments(call, spec);
        if (invalid != null) {
            return invalid;
        }

        if (SyncSurface.MESSAGE_SEND.equals(call.action())) {
            // The message path stays in the per-sink queue so directed delivery
            // keeps the same isolation and retry policy as a fan-out.
            return await(dispatchCall(ctx, call, spec));
        }
        return executeBounded(ctx.sink, call, effectiveTimeout(spec));
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

        SinkContext ctx = sinks.get(backend);
        if (ctx == null) {
            logger.warn("SyncBus: backend '" + backend + "' not found for directed send");
            return List.of(new SyncResult.Unsupported(
                    "backend '" + backend + "' is not registered"));
        }
        if (state.get() != LifecycleState.RUNNING) {
            return List.of(new SyncResult.HostFailure(
                    "sync bus is not running; backend '" + backend
                            + "' did not receive the message"));
        }
        return List.of(await(dispatchCall(ctx, messageSendCall(backend, message),
                sendSpec(ctx))));
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

    private static SyncActionSpec sendSpec(SinkContext ctx) {
        return ctx.surface.action(SyncSurface.MESSAGE_SEND)
                .orElseThrow(() -> new IllegalStateException(
                        "backend '" + ctx.surface.backend() + "' does not publish "
                                + SyncSurface.MESSAGE_SEND));
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
        CompletableFuture<SyncResult> outcome;
        try {
            outcome = CompletableFuture.supplyAsync(() -> sink.invoke(call), invocationExecutor);
        } catch (RejectedExecutionException stopped) {
            return new SyncResult.HostFailure(
                    "sync bus invocation executor is not running");
        }

        try {
            SyncResult result = outcome.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return result != null
                    ? result
                    : new SyncResult.HostFailure("sink returned no result");
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

    private static SyncResult authorize(SyncCall call, SyncActionSpec spec) {
        if (call.principal() instanceof Principal.Official) {
            return isLcmAction(spec.id())
                    ? null
                    : new SyncResult.Denied(
                            "Principal.Official may only invoke the least common multiple");
        }
        return new SyncResult.Denied(
                "program grants are not configured; principal "
                        + principalId(call.principal()) + " is denied");
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
                || SyncSurface.MESSAGE_SEND.equals(action);
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

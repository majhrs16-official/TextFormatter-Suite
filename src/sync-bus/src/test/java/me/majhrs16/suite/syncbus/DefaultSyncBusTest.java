package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncActionSpec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncParamSpec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.api.sync.SyncValueType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DefaultSyncBusTest {

    private static final Principal OFFICIAL = new Principal.Official("suite.test");

    private final PluginLogger logger = new NoopLogger();

    @Test
    void registersSinksAndRejectsDuplicateNames() {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        try {
            bus.register(new RecordingSink("one"));

            assertTrue(bus.sinkNames().contains("one"));
            assertEquals(1, bus.sinks().size());
            assertThrows(IllegalStateException.class, () -> bus.register(new RecordingSink("one")));
        } finally {
            bus.stop();
        }
    }

    @Test
    void rejectsSurfacesThatDoNotMatchTheSinkName() {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        CustomSurfaceSink sink = new CustomSurfaceSink(
                "one",
                SyncSurface.lcm("other", SemVer.of(1, 0, 0)),
                call -> new SyncResult.Ok(null));
        try {
            assertThrows(IllegalArgumentException.class, () -> bus.register(sink));
        } finally {
            bus.stop();
        }
    }

    @Test
    void rejectsSurfacesWithoutLeastCommonMultiple() {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        SyncSurface partial = new SyncSurface("one", SemVer.of(1, 0, 0), Set.of(
                new SyncActionSpec("channel.create", ActionKind.WRITE, List.of(),
                        null, false, 1_000)));
        CustomSurfaceSink sink = new CustomSurfaceSink(
                "one", partial, call -> new SyncResult.Ok(null));
        try {
            assertThrows(IllegalArgumentException.class, () -> bus.register(sink));
        } finally {
            bus.stop();
        }
    }

    @Test
    void rejectsSendsBeforeStartAndAfterUnregister() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        try {
            bus.register(new RecordingSink("one"));

            List<SyncResult> beforeStart = bus.send(Message.builder().text("before start").build());
            assertEquals(1, beforeStart.size());
            assertTrue(beforeStart.get(0) instanceof SyncResult.HostFailure);

            assertTrue(bus.unregister("one"));
            assertFalse(bus.unregister("one"));
            assertTrue(bus.sinkNames().isEmpty());
            assertTrue(bus.send(Message.builder().text("after unregister").build()).isEmpty());
        } finally {
            bus.stop();
        }
    }

    @Test
    void startsDeliversAndStopsWithoutBlocking() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        RecordingSink sink = new RecordingSink("one");
        bus.register(sink);
        bus.start();
        try {
            Message message = Message.builder().text("hello").build();
            List<SyncResult> results = bus.send(message);
            assertEquals(1, results.size());
            assertTrue(results.get(0).isOk());
            assertTrue(sink.delivered.await(5, TimeUnit.SECONDS));
            assertSame(message, sink.received);
            assertEquals(1L, bus.getTotalBroadcast());
        } finally {
            long started = System.nanoTime();
            bus.stop();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 2_000, "stop took " + elapsedMs + "ms");
        }
        assertTrue(sink.stopped);
        List<SyncResult> afterStop = bus.send(Message.builder().text("after stop").build());
        assertEquals(1, afterStop.size());
        assertTrue(afterStop.get(0) instanceof SyncResult.HostFailure);
    }

    @Test
    void stopsCleanlyFromNewState() {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        bus.stop();
        bus.stop();

        assertTrue(bus.isEmpty());
        assertTrue(bus.send(Message.builder().text("stopped").build()).isEmpty());
    }

    @Test
    void startsSinkRegisteredAfterBusStartAndStopsItOnUnregister() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        bus.start();
        try {
            RecordingSink sink = new RecordingSink("late");
            bus.register(sink);

            assertTrue(sink.started,
                    "a sink registered after start() must be started immediately");
            List<SyncResult> results = bus.send(Message.builder().text("late").build());
            assertEquals(1, results.size());
            assertTrue(results.get(0).isOk());
            assertTrue(sink.delivered.await(5, TimeUnit.SECONDS));

            assertTrue(bus.unregister("late"));
            assertTrue(sink.stopped);
            assertFalse(bus.sinkNames().contains("late"));
        } finally {
            bus.stop();
        }
    }

    @Test
    void failedLateSinkStartIsRolledBack() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        bus.start();
        try {
            assertThrows(IllegalStateException.class,
                    () -> bus.register(new FailingStartSink("broken")));
            assertFalse(bus.sinkNames().contains("broken"),
                    "a sink that cannot start must not stay registered");
        } finally {
            bus.stop();
        }
    }

    @Test
    void sendReportsFailureWhenQueueIsFullAndAllowsRetry() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger, 10_000, 1);
        BlockingSink sink = new BlockingSink("blocked");
        bus.register(sink);
        bus.start();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Message first = Message.builder().text("first").build();
            Future<List<SyncResult>> firstResult = callers.submit(() -> bus.send(first));
            assertTrue(sink.firstSendEntered.await(5, TimeUnit.SECONDS),
                    "the sink worker must be blocked in send() before filling the queue");

            Message second = Message.builder().text("second").build();
            Future<List<SyncResult>> secondResult = callers.submit(() -> bus.send(second));
            awaitUntil(() -> bus.queuedActions("blocked") == 1, 5, TimeUnit.SECONDS);

            Message rejected = Message.builder().text("rejected").build();
            List<SyncResult> rejectedResults = bus.send(rejected);
            assertEquals(1, rejectedResults.size());
            assertTrue(rejectedResults.get(0) instanceof SyncResult.HostFailure,
                    "a full sink queue must return an explicit failure");
            assertTrue(bus.getTotalDropped() > 0, "dropped deliveries must be counted");

            sink.release.countDown();
            assertTrue(firstResult.get(5, TimeUnit.SECONDS).get(0).isOk());
            assertTrue(secondResult.get(5, TimeUnit.SECONDS).get(0).isOk());

            List<SyncResult> retried = bus.send(rejected);
            assertEquals(1, retried.size());
            assertTrue(retried.get(0).isOk(),
                    "a message rejected by every sink must be retryable");
        } finally {
            sink.release.countDown();
            callers.shutdownNow();
            bus.stop();
        }
    }

    @Test
    void sendWaitsForThisMessageNotForAnEmptyQueue() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger, 10_000);
        GatedSink sink = new GatedSink("gated");
        bus.register(sink);
        bus.start();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Message first = Message.builder().text("first").build();
            Future<List<SyncResult>> firstResult = callers.submit(() -> bus.send(first));
            assertTrue(sink.firstEntered.await(5, TimeUnit.SECONDS),
                    "the first send must be in progress before queueing the second");

            Message second = Message.builder().text("second").build();
            Future<List<SyncResult>> secondResult = callers.submit(() -> bus.send(second));
            awaitUntil(() -> bus.queuedActions("gated") == 1, 5, TimeUnit.SECONDS);
            assertFalse(secondResult.isDone(),
                    "the second send must not complete while its message is still queued");

            sink.releaseFirst.countDown();
            assertTrue(firstResult.get(5, TimeUnit.SECONDS).get(0).isOk());
            assertTrue(sink.secondEntered.await(5, TimeUnit.SECONDS),
                    "the worker must pick up the second message");

            assertFalse(secondResult.isDone(),
                    "the second send must complete after its own send returns");

            sink.releaseSecond.countDown();
            assertTrue(secondResult.get(5, TimeUnit.SECONDS).get(0).isOk());
            assertEquals(List.of(first.id().toString(), second.id().toString()), sink.received);
        } finally {
            sink.releaseFirst.countDown();
            sink.releaseSecond.countDown();
            callers.shutdownNow();
            bus.stop();
        }
    }

    @Test
    void sendTimesOutWhenTheSinkNeverReturns() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger, 200);
        BlockingSink sink = new BlockingSink("blocked");
        bus.register(sink);
        bus.start();
        try {
            List<SyncResult> results = bus.send(
                    Message.builder().text("stuck").build());
            assertEquals(1, results.size());
            assertTrue(results.get(0) instanceof SyncResult.Timeout,
                    "a wedged sink must fail with an explicit timeout, got: " + results.get(0));
        } finally {
            sink.release.countDown();
            bus.stop();
        }
    }

    @Test
    void sendFailsAfterRetriesAreExhausted() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger, 10_000);
        FailingSink sink = new FailingSink("broken");
        bus.register(sink);
        bus.start();
        try {
            List<SyncResult> results = bus.send(
                    Message.builder().text("fail").build());
            assertEquals(1, results.size());
            assertTrue(results.get(0) instanceof SyncResult.HostFailure);
            assertEquals(3, bus.getTotalRetries());
            assertEquals(1L, bus.getTotalDropped());
            assertEquals(4, sink.attempts.get());
        } finally {
            bus.stop();
        }
    }

    @Test
    void duplicateReturnsNoResultsAndDoesNotDeliverAgain() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger, 10_000);
        RecordingSink sink = new RecordingSink("one");
        bus.register(sink);
        bus.start();
        try {
            Message message = Message.builder().text("only once").build();
            List<SyncResult> first = bus.send(message);
            assertEquals(1, first.size());
            assertTrue(first.get(0).isOk());

            assertTrue(bus.send(message).isEmpty(),
                    "a duplicate inside the dedup window must not fabricate a fresh delivery");
            assertEquals(1L, bus.getTotalDeduped());
            assertEquals(1L, bus.getTotalBroadcast());
        } finally {
            bus.stop();
        }
    }

    @Test
    void invokeServesBackendInfoAndMessageSend() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        RecordingSink sink = new RecordingSink("one");
        bus.register(sink);
        bus.start();
        try {
            SyncResult info = bus.invoke(new SyncCall(
                    "one", SyncSurface.BACKEND_INFO, Map.of(), OFFICIAL, "", "metadata"));
            assertTrue(info instanceof SyncResult.Ok);
            Object value = ((SyncResult.Ok) info).value();
            assertTrue(value instanceof Map<?, ?>);
            assertEquals("one", ((Map<?, ?>) value).get("name"));

            Message message = Message.builder().text("hello").build();
            SyncResult sent = bus.invoke(new SyncCall(
                    "one", SyncSurface.MESSAGE_SEND, Map.of("message", message),
                    OFFICIAL, "key-1", "delivery"));
            assertTrue(sent.isOk());
            assertSame(message, sink.received);
            assertTrue(sink.delivered.await(5, TimeUnit.SECONDS));
        } finally {
            bus.stop();
        }
    }

    @Test
    void invokeDeniesOfficialNativeActionsAndPrograms() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        CustomSurfaceSink sink = new CustomSurfaceSink(
                "native", surfaceWithNative("native"), call -> new SyncResult.Ok(null));
        bus.register(sink);
        bus.start();
        try {
            SyncCall nativeCall = new SyncCall(
                    "native", "channel.create", Map.of("name", "room"),
                    OFFICIAL, "", "native");
            assertTrue(bus.invoke(nativeCall) instanceof SyncResult.Denied,
                    "Principal.Official must not invoke native actions");

            SyncCall programCall = new SyncCall(
                    "native", SyncSurface.MESSAGE_SEND,
                    Map.of("message", Message.builder().text("hello").build()),
                    new Principal.Program("a".repeat(64)), "key", "program");
            assertTrue(bus.invoke(programCall) instanceof SyncResult.Denied,
                    "a program without grants must be denied");
        } finally {
            bus.stop();
        }
    }

    @Test
    void invokeRejectsUnknownBackendAndUnpublishedAction() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        bus.register(new RecordingSink("one"));
        bus.start();
        try {
            assertTrue(bus.invoke(new SyncCall(
                    "missing", SyncSurface.BACKEND_INFO, Map.of(), OFFICIAL, "", "test"))
                    instanceof SyncResult.Unsupported);
            assertTrue(bus.invoke(new SyncCall(
                    "one", "no.such.action", Map.of(), OFFICIAL, "", "test"))
                    instanceof SyncResult.Unsupported);
        } finally {
            bus.stop();
        }
    }

    @Test
    void invokeValidatesArguments() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        bus.register(new RecordingSink("one"));
        bus.start();
        try {
            Message message = Message.builder().text("hello").build();

            SyncResult missing = bus.invoke(new SyncCall(
                    "one", SyncSurface.MESSAGE_SEND, Map.of(), OFFICIAL, "", "test"));
            assertTrue(missing instanceof SyncResult.InvalidArgument);

            SyncResult unknown = bus.invoke(new SyncCall(
                    "one", SyncSurface.MESSAGE_SEND,
                    Map.of("message", message, "extra", "value"), OFFICIAL, "", "test"));
            assertTrue(unknown instanceof SyncResult.InvalidArgument);

            SyncResult wrongType = bus.invoke(new SyncCall(
                    "one", SyncSurface.MESSAGE_SEND,
                    Map.of("message", "not-a-message"), OFFICIAL, "", "test"));
            assertTrue(wrongType instanceof SyncResult.InvalidArgument);
        } finally {
            bus.stop();
        }
    }

    @Test
    void invokeTimesOutANativeMetadataAction() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger, 10_000);
        CustomSurfaceSink sink = new CustomSurfaceSink(
                "slow",
                shortInfoSurface("slow", 100),
                call -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return new SyncResult.Ok(null);
                });
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(new SyncCall(
                    "slow", SyncSurface.BACKEND_INFO, Map.of(), OFFICIAL, "", "slow info"));
            assertTrue(result instanceof SyncResult.Timeout,
                    "a native action must honour its declared timeout, got: " + result);
        } finally {
            bus.stop();
        }
    }

    @Test
    void broadcastFansOutOnlyTheLeastCommonMultiple() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        RecordingSink one = new RecordingSink("one");
        RecordingSink two = new RecordingSink("two");
        bus.register(one);
        bus.register(two);
        bus.start();
        try {
            Message message = Message.builder().text("broadcast").build();
            SyncCall send = new SyncCall(
                    "ignored", SyncSurface.MESSAGE_SEND, Map.of("message", message),
                    OFFICIAL, "", "broadcast");
            List<SyncResult> sent = bus.broadcast(send);
            assertEquals(2, sent.size());
            assertTrue(sent.stream().allMatch(SyncResult::isOk));
            assertSame(message, one.received);
            assertSame(message, two.received);

            SyncCall nativeCall = new SyncCall(
                    "ignored", "channel.create", Map.of(), OFFICIAL, "", "broadcast");
            List<SyncResult> rejected = bus.broadcast(nativeCall);
            assertEquals(2, rejected.size());
            assertTrue(rejected.stream().allMatch(result -> result instanceof SyncResult.Unsupported),
                    "broadcast must never turn a native action into a mass operation");
        } finally {
            bus.stop();
        }
    }

    @Test
    void sendToUnknownBackendReturnsUnsupported() {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        try {
            List<SyncResult> results = bus.sendTo(
                    "missing", Message.builder().text("hello").build());
            assertEquals(1, results.size());
            assertTrue(results.get(0) instanceof SyncResult.Unsupported);
        } finally {
            bus.stop();
        }
    }

    @Test
    void surfacesKeepRegistrationOrder() {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        try {
            bus.register(new RecordingSink("one"));
            bus.register(new RecordingSink("two"));
            assertEquals(List.of("one", "two"),
                    bus.surfaces().stream().map(SyncSurface::backend).toList());
        } finally {
            bus.stop();
        }
    }

    @Test
    void discoversPublishedSinkServicesAndDropsThemWhenTheyDisappear() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        FakeRegistry registry = new FakeRegistry();
        RecordingSink sink = new RecordingSink("discovered");
        registry.add(SyncSink.class, sink);

        bus.discoverSinks(registry);
        bus.start();
        try {
            assertEquals(List.of("discovered"), List.copyOf(bus.sinkNames()));
            assertTrue(sink.started, "a discovered sink must be started with the bus");

            bus.discoverSinks(registry);
            assertEquals(1, bus.sinkNames().size(), "discovery must be idempotent by sink name");

            registry.remove(SyncSink.class, sink);
            bus.discoverSinks(registry);

            assertTrue(bus.sinkNames().isEmpty(),
                    "a sink whose service disappeared must be unregistered");
            assertTrue(sink.stopped, "an unregistered discovered sink must be stopped");
        } finally {
            bus.stop();
        }
    }

    private static SyncSurface surfaceWithNative(String backend) {
        SyncSurface lcm = SyncSurface.lcm(backend, SemVer.of(1, 0, 0));
        Set<SyncActionSpec> actions = new LinkedHashSet<>(lcm.actions());
        actions.add(new SyncActionSpec(
                "channel.create",
                ActionKind.WRITE,
                List.of(SyncParamSpec.required("name", SyncValueType.STRING)),
                null,
                false,
                1_000));
        return new SyncSurface(backend, lcm.contractVersion(), actions);
    }

    private static SyncSurface shortInfoSurface(String backend, long timeoutMillis) {
        SyncSurface lcm = SyncSurface.lcm(backend, SemVer.of(1, 0, 0));
        Set<SyncActionSpec> actions = new LinkedHashSet<>();
        for (SyncActionSpec spec : lcm.actions()) {
            if (SyncSurface.BACKEND_INFO.equals(spec.id())) {
                actions.add(new SyncActionSpec(
                        spec.id(), spec.kind(), spec.params(), spec.result(),
                        spec.idempotent(), timeoutMillis));
            } else {
                actions.add(spec);
            }
        }
        return new SyncSurface(backend, lcm.contractVersion(), actions);
    }

    private static void awaitUntil(BooleanSupplier condition, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("condition not met within " + timeout + " " + unit);
    }

    private static final class FakeRegistry implements ServiceRegistry {

        private final Map<Class<?>, List<Object>> services = new HashMap<>();

        <T> void add(Class<T> type, T service) {
            services.computeIfAbsent(type, ignored -> new ArrayList<>()).add(service);
        }

        <T> void remove(Class<T> type, T service) {
            List<Object> registered = services.get(type);
            if (registered != null) {
                registered.remove(service);
            }
        }

        @Override
        public <T> Registration register(Class<T> type, T service) {
            add(type, service);
            return () -> remove(type, service);
        }

        @Override
        public <T> Optional<T> find(Class<T> type) {
            return findAll(type).stream().findFirst();
        }

        @Override
        public <T> List<T> findAll(Class<T> type) {
            List<Object> registered = services.get(type);
            return registered == null
                    ? List.of()
                    : registered.stream().map(type::cast).toList();
        }
    }

    private static final class NoopLogger implements PluginLogger {
        @Override public void info(String message, Object... args) {}
        @Override public void warn(String message, Object... args) {}
        @Override public void error(String message, Object... args) {}
        @Override public void error(String message, Throwable throwable) {}
        @Override public void debug(String message, Object... args) {}
    }

    private static final class RecordingSink implements SyncSink {
        private final String name;
        private final CountDownLatch delivered = new CountDownLatch(1);
        private volatile Message received;
        private volatile boolean started;
        private volatile boolean stopped;

        RecordingSink(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public DeliverySemantics deliverySemantics() { return DeliverySemantics.AT_LEAST_ONCE; }
        @Override public Ordering ordering() { return Ordering.PER_CHANNEL; }
        @Override public void start() { started = true; }
        @Override public void stop() { stopped = true; }
        @Override public void send(Message message) {
            received = message;
            delivered.countDown();
        }
        @Override public void setListener(SyncListener listener) { assertNotNull(listener); }
    }

    private static final class CustomSurfaceSink implements SyncSink {
        private final String name;
        private final SyncSurface surface;
        private final Function<SyncCall, SyncResult> handler;

        CustomSurfaceSink(String name, SyncSurface surface,
                          Function<SyncCall, SyncResult> handler) {
            this.name = name;
            this.surface = surface;
            this.handler = handler;
        }

        @Override public String name() { return name; }
        @Override public SyncSurface surface() { return surface; }
        @Override public SyncResult invoke(SyncCall call) { return handler.apply(call); }
        @Override public DeliverySemantics deliverySemantics() { return DeliverySemantics.AT_LEAST_ONCE; }
        @Override public Ordering ordering() { return Ordering.PER_CHANNEL; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public void send(Message message) {}
        @Override public void setListener(SyncListener listener) {}
    }

    private static final class BlockingSink implements SyncSink {
        private final String name;
        private final CountDownLatch firstSendEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger sent = new AtomicInteger();

        BlockingSink(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public DeliverySemantics deliverySemantics() { return DeliverySemantics.AT_LEAST_ONCE; }
        @Override public Ordering ordering() { return Ordering.PER_CHANNEL; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void setListener(SyncListener listener) { }

        @Override
        public void send(Message message) {
            if (sent.get() == 0) {
                firstSendEntered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("sink was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("sink interrupted while blocked", e);
                }
            }
            sent.incrementAndGet();
        }
    }

    private static final class FailingStartSink implements SyncSink {
        private final String name;

        FailingStartSink(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public DeliverySemantics deliverySemantics() { return DeliverySemantics.AT_LEAST_ONCE; }
        @Override public Ordering ordering() { return Ordering.PER_CHANNEL; }
        @Override public void start() throws Exception { throw new Exception("cannot start"); }
        @Override public void stop() {}
        @Override public void send(Message message) {}
        @Override public void setListener(SyncListener listener) { }
    }

    private static final class GatedSink implements SyncSink {
        private final String name;
        private final List<String> received = java.util.Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch secondEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch releaseSecond = new CountDownLatch(1);

        GatedSink(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public DeliverySemantics deliverySemantics() { return DeliverySemantics.AT_LEAST_ONCE; }
        @Override public Ordering ordering() { return Ordering.PER_CHANNEL; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public void setListener(SyncListener listener) {}

        @Override
        public void send(Message message) {
            int index = received.size();
            received.add(message.id().toString());
            try {
                if (index == 0) {
                    firstEntered.countDown();
                    releaseFirst.await(10, TimeUnit.SECONDS);
                } else if (index == 1) {
                    secondEntered.countDown();
                    releaseSecond.await(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("gated sink interrupted", e);
            }
        }
    }

    private static final class FailingSink implements SyncSink {
        private final String name;
        private final AtomicInteger attempts = new AtomicInteger();

        FailingSink(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public DeliverySemantics deliverySemantics() { return DeliverySemantics.AT_LEAST_ONCE; }
        @Override public Ordering ordering() { return Ordering.PER_CHANNEL; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public void setListener(SyncListener listener) {}

        @Override
        public void send(Message message) {
            attempts.incrementAndGet();
            throw new IllegalStateException("send failed");
        }
    }
}

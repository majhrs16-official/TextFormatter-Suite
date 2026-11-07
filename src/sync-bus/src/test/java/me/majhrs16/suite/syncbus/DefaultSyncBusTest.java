package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSyncBusTest {

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
    void rejectsBroadcastsBeforeStartAndAfterUnregister() {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        try {
            bus.register(new RecordingSink("one"));

            assertEquals(0, bus.broadcast(Message.builder().text("before start").build()));
            assertTrue(bus.broadcastAsync(Message.builder().text("before start").build()).isEmpty());

            assertTrue(bus.unregister("one"));
            assertFalse(bus.unregister("one"));
            assertTrue(bus.sinkNames().isEmpty());
            assertEquals(0, bus.broadcast(Message.builder().text("after unregister").build()));
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
            assertEquals(1, bus.broadcast(message));
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
        assertEquals(0, bus.broadcast(Message.builder().text("after stop").build()));
    }

    @Test
    void stopsCleanlyFromNewState() {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        bus.stop();
        bus.stop();

        assertTrue(bus.isEmpty());
        assertEquals(0, bus.broadcast(Message.builder().text("stopped").build()));
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
            assertEquals(1, bus.broadcast(Message.builder().text("late").build()));
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
    void broadcastReportsZeroWhenNoSinkAcceptsAndAllowsRetry() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger);
        BlockingSink sink = new BlockingSink("blocked");
        bus.register(sink);
        bus.start();
        try {
            // First message occupies the single worker thread, so every later
            // message can only wait in the 5.000-slot queue.
            assertEquals(1, bus.broadcast(Message.builder().text("blocker").build()));
            assertTrue(sink.firstSendEntered.await(5, TimeUnit.SECONDS),
                    "the sink worker must be blocked in send() before filling the queue");

            int accepted = 1;
            Message dropped = null;
            for (int i = 0; i < 6000 && dropped == null; i++) {
                Message candidate = Message.builder().text("fill " + i).build();
                if (bus.broadcast(candidate) == 0) {
                    dropped = candidate;
                } else {
                    accepted++;
                }
            }
            assertNotNull(dropped, "a full sink queue must make broadcast report zero accepted sinks");
            assertTrue(bus.getTotalDropped() > 0, "dropped deliveries must be counted");
            assertTrue(accepted > 0, "the accepted counter must track real enqueues");

            sink.release.countDown();
            assertTrue(sink.awaitSent(accepted, 10, TimeUnit.SECONDS),
                    "all accepted messages must be delivered after the queue drains");

            // The rejected message was not marked as sent: retrying it must work.
            assertEquals(1, bus.broadcast(dropped),
                    "a broadcast rejected by every sink must be retryable");
            assertTrue(sink.awaitSent(accepted + 1, 5, TimeUnit.SECONDS));
        } finally {
            sink.release.countDown();
            bus.stop();
        }
    }

    @Test
    void asyncFutureWaitsForThisMessageNotForAnEmptyQueue() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger, 10_000);
        GatedSink sink = new GatedSink("gated");
        bus.register(sink);
        bus.start();
        try {
            Message first = Message.builder().text("first").build();
            CompletableFuture<Void> firstFuture = bus.broadcastAsync(first).get("gated");
            assertNotNull(firstFuture);
            assertTrue(sink.firstEntered.await(5, TimeUnit.SECONDS),
                    "the first send must be in progress before queueing the second");

            Message second = Message.builder().text("second").build();
            CompletableFuture<Void> secondFuture = bus.broadcastAsync(second).get("gated");
            assertNotNull(secondFuture);

            sink.releaseFirst.countDown();
            firstFuture.get(5, TimeUnit.SECONDS);
            assertTrue(sink.secondEntered.await(5, TimeUnit.SECONDS),
                    "the worker must pick up the second message");

            assertFalse(secondFuture.isDone(),
                    "the future must complete after send() returns, not when the queue looks empty");

            sink.releaseSecond.countDown();
            secondFuture.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(first.id().toString(), second.id().toString()), sink.received);
        } finally {
            sink.releaseFirst.countDown();
            sink.releaseSecond.countDown();
            bus.stop();
        }
    }

    @Test
    void asyncFutureTimesOutWhenTheSinkNeverReturns() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger, 200);
        BlockingSink sink = new BlockingSink("blocked");
        bus.register(sink);
        bus.start();
        try {
            CompletableFuture<Void> future = bus.broadcastAsync(
                    Message.builder().text("stuck").build()).get("blocked");
            assertNotNull(future);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TimeoutException,
                    "a wedged sink must fail the future with an explicit timeout, got: " + failure.getCause());
        } finally {
            sink.release.countDown();
            bus.stop();
        }
    }

    @Test
    void asyncFutureFailsAfterRetriesAreExhausted() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger, 10_000);
        FailingSink sink = new FailingSink("broken");
        bus.register(sink);
        bus.start();
        try {
            CompletableFuture<Void> future = bus.broadcastAsync(
                    Message.builder().text("fail").build()).get("broken");
            assertNotNull(future);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals(3, bus.getTotalRetries());
            assertEquals(1L, bus.getTotalDropped());
            assertEquals(4, sink.attempts.get());
        } finally {
            bus.stop();
        }
    }

    @Test
    void asyncDuplicateReturnsNoFuturesAndDoesNotDeliverAgain() throws Exception {
        DefaultSyncBus bus = new DefaultSyncBus(logger, 10_000);
        RecordingSink sink = new RecordingSink("one");
        bus.register(sink);
        bus.start();
        try {
            Message message = Message.builder().text("only once").build();
            CompletableFuture<Void> first = bus.broadcastAsync(message).get("one");
            assertNotNull(first);
            first.get(5, TimeUnit.SECONDS);

            assertTrue(bus.broadcastAsync(message).isEmpty(),
                    "a duplicate inside the dedup window must not fabricate a fresh delivery");
            assertEquals(1L, bus.getTotalDeduped());
            assertEquals(1L, bus.getTotalBroadcast());
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

        boolean awaitSent(int expected, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (sent.get() < expected) {
                if (System.nanoTime() > deadline) {
                    return false;
                }
                Thread.sleep(10);
            }
            return true;
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

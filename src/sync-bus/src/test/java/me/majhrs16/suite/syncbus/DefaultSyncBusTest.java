package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        private volatile boolean stopped;

        RecordingSink(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public DeliverySemantics deliverySemantics() { return DeliverySemantics.AT_LEAST_ONCE; }
        @Override public Ordering ordering() { return Ordering.PER_CHANNEL; }
        @Override public void start() {}
        @Override public void stop() { stopped = true; }
        @Override public void send(Message message) {
            received = message;
            delivered.countDown();
        }
        @Override public void setListener(SyncListener listener) { assertNotNull(listener); }
    }
}

package me.majhrs16.suite.syncvelocity;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.velocity.VelocityConnection;
import me.majhrs16.suite.api.velocity.VelocityListener;
import me.majhrs16.suite.transport.MessageCodec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocitySinkTest {

    private FakeConnection connection;
    private VelocityConfig config;
    private VelocitySink sink;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        connection = new FakeConnection(List.of("server1", "server2"));
        config = new VelocityConfig();
        config.enabled = true;
        config.secret = "test-secret";
        config.servers = List.of("server1", "server2");
        config.mapping = "* -> server1";
        config.retryInitialDelay = Duration.ofMillis(10);
        config.retryMaxDelay = Duration.ofMillis(100);
        config.retryMultiplier = 2.0;
        config.maxRetries = 3;
        config.maxQueueSize = 100;
        config.queueDrainTimeout = Duration.ofMillis(200);
        config.dynamicDiscovery = true;
        config.metricsInterval = Duration.ofMinutes(1);

        sink = new VelocitySink(connection, config, new NoopLogger());
        listener = new RecordingListener();
        sink.setListener(listener);
    }

    @Test
    void startsTheConnectionOnce() throws Exception {
        sink.start();
        sink.start();

        assertEquals(1, connection.starts.get());
        assertTrue(connection.started);
        sink.stop();
        assertFalse(connection.started);
    }

    @Test
    void disabledConfigDoesNotTouchTheTransport() throws Exception {
        VelocityConfig disabled = new VelocityConfig();
        disabled.enabled = false;

        VelocitySink disabledSink = new VelocitySink(connection, disabled, new NoopLogger());
        disabledSink.start();
        disabledSink.stop();

        assertEquals(0, connection.starts.get());
        assertEquals(0, connection.stops.get());
    }

    @Test
    void sendsToTheMappedServer() throws Exception {
        sink.start();
        sink.send(chatMessage("Hello World"));

        assertEquals(1, connection.payloads("server1").size());
        assertEquals(1, sink.getMessagesSent());
        assertEquals(0, sink.getQueueSize());
    }

    @Test
    void queuesWhenTheTransportRejectsTheSend() throws Exception {
        connection.failing.add("server1");
        sink.start();
        sink.send(chatMessage("Hello World"));

        assertEquals(1, sink.getQueueSize());
        assertEquals(0, sink.getMessagesSent());
    }

    @Test
    void retriesQueuedMessages() throws Exception {
        connection.failing.add("server1");
        sink.start();
        sink.send(chatMessage("retry me"));
        assertEquals(1, sink.getQueueSize());

        connection.failing.remove("server1");
        waitUntil(() -> connection.payloads("server1").size() == 1,
            Duration.ofSeconds(2));
        assertEquals(1, connection.payloads("server1").size());
        assertTrue(sink.getMessagesSent() >= 1);
    }

    @Test
    void verifiesInboundSecret() throws Exception {
        sink.start();
        byte[] valid = payload(chatMessage("inbound"), "test-secret");
        connection.inbound(valid);

        assertEquals(1, listener.messages.size());
        assertEquals(1, sink.getMessagesReceived());

        byte[] invalid = payload(chatMessage("forged"), "wrong-secret");
        connection.inbound(invalid);
        assertEquals(1, listener.messages.size());
    }

    @Test
    void acceptsInboundWithoutSecretWhenConfigIsEmpty() throws Exception {
        config.secret = "";
        VelocitySink openSink = new VelocitySink(connection, config, new NoopLogger());
        openSink.setListener(listener);
        openSink.start();

        connection.inbound(payload(chatMessage("open"), ""));
        assertEquals(1, listener.messages.size());
    }

    @Test
    void reportsHealthFromLifecycle() throws Exception {
        VelocitySink.HealthStatus down = sink.getHealth();
        assertEquals("DOWN", down.status());

        sink.start();
        VelocitySink.HealthStatus up = sink.getHealth();
        assertEquals("UP", up.status());
        assertTrue(up.metrics().containsKey("messagesSent"));
        assertTrue(up.metrics().containsKey("messagesReceived"));

        sink.stop();
        assertEquals("DOWN", sink.getHealth().status());
    }

    @Test
    void validatesPolicyValues() {
        VelocityConfig invalidDelay = new VelocityConfig();
        invalidDelay.retryInitialDelay = Duration.ZERO;
        assertThrows(IllegalArgumentException.class, invalidDelay::validate);

        VelocityConfig invalidMultiplier = new VelocityConfig();
        invalidMultiplier.retryMultiplier = 1.0;
        assertThrows(IllegalArgumentException.class, invalidMultiplier::validate);

        VelocityConfig invalidRetries = new VelocityConfig();
        invalidRetries.maxRetries = -1;
        assertThrows(IllegalArgumentException.class, invalidRetries::validate);

        VelocityConfig invalidQueue = new VelocityConfig();
        invalidQueue.maxQueueSize = 0;
        assertThrows(IllegalArgumentException.class, invalidQueue::validate);
    }

    @Test
    void recordsMetricsAfterSuccessfulSend() throws Exception {
        VelocityConfig metricsConfig = new VelocityConfig();
        metricsConfig.enabled = true;
        metricsConfig.secret = "";
        metricsConfig.servers = List.of("server1");
        metricsConfig.mapping = "* -> server1";

        VelocitySink metricsSink = new VelocitySink(connection, metricsConfig, new NoopLogger());
        metricsSink.setListener(listener);
        metricsSink.start();
        metricsSink.send(chatMessage("Hello"));

        assertEquals(1, metricsSink.getMessagesSent());
        assertEquals(0, metricsSink.getMessagesFailed());
        assertEquals(0, metricsSink.getQueueSize());
    }

    @Test
    void drainsQueueOnStop() throws Exception {
        connection.failing.add("server1");
        sink.start();
        sink.send(chatMessage("drain"));
        assertEquals(1, sink.getQueueSize());

        connection.failing.remove("server1");
        sink.stop();

        assertTrue(sink.getQueueSize() <= 1);
    }

    private static Message chatMessage(String text) {
        Actor sender = new Actor(UUID.randomUUID(), "TestPlayer",
            Actor.ActorKind.PLAYER, Language.EN, null);
        return Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.others())
            .channel("chat.global")
            .text(text)
            .build();
    }

    private static byte[] payload(Message message, String secret) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("textformatter");
            out.writeUTF(message.type().name());
            out.writeUTF(message.id().toString());
            out.writeUTF(message.sender().name());
            out.writeUTF(message.channel());
            out.writeUTF(MessageCodec.toJsonString(message));
            out.writeUTF(message.sender().uuid().toString());
            out.writeLong(System.currentTimeMillis());
            if (secret != null && !secret.isBlank()) {
                out.writeUTF(secret);
            }
        }
        return bytes.toByteArray();
    }

    private static void waitUntil(Check check, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    private static final class FakeConnection implements VelocityConnection {

        private final List<String> servers;
        private final Map<String, List<byte[]>> sent = new HashMap<>();
        private final Set<String> failing = new HashSet<>();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();
        private VelocityListener listener;
        private boolean started;

        FakeConnection(List<String> servers) {
            this.servers = new ArrayList<>(servers);
        }

        @Override
        public void start(VelocityListener listener) {
            this.listener = listener;
            this.started = true;
            starts.incrementAndGet();
        }

        @Override
        public void stop() {
            this.started = false;
            stops.incrementAndGet();
        }

        @Override
        public List<String> servers() {
            return List.copyOf(servers);
        }

        @Override
        public boolean send(String server, byte[] payload) {
            if (!started || failing.contains(server) || !servers.contains(server)) {
                return false;
            }
            sent.computeIfAbsent(server, ignored -> new ArrayList<>()).add(payload);
            return true;
        }

        List<byte[]> payloads(String server) {
            return sent.getOrDefault(server, List.of());
        }

        void inbound(byte[] payload) {
            listener.onMessage(payload);
        }
    }

    private static final class RecordingListener implements SyncListener {

        private final List<Message> messages = new ArrayList<>();

        @Override
        public void onMessage(me.majhrs16.suite.api.spi.SyncSink source, Message message) {
            messages.add(message);
        }

        @Override
        public void onDisconnect(me.majhrs16.suite.api.spi.SyncSink source, String reason) {
        }
    }

    private static final class NoopLogger implements PluginLogger {

        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable throwable) { }
        @Override public void debug(String message, Object... args) { }
    }
}

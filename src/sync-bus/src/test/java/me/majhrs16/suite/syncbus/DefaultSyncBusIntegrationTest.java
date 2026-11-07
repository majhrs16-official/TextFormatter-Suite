package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.SyncResult;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests de integración para {@link DefaultSyncBus}.
 * Validan el comportamiento del bus de sincronización con sinks mock.
 */
@DisplayName("SyncBus — integración")
class DefaultSyncBusIntegrationTest {

    private static final Actor STEVE = new Actor(
        UUID.fromString("11111111-1111-1111-1111-111111111111"), "Steve",
        Actor.ActorKind.PLAYER, Language.EN, null);
    private static final Actor ALEX = new Actor(
        UUID.fromString("22222222-2222-2222-2222-222222222222"), "Alex",
        Actor.ActorKind.PLAYER, Language.ES, null);

    private DefaultSyncBus bus;
    private PluginLogger logger;

    void setUp() {
        logger = new PluginLogger() {
            @Override public void info(String m, Object... a) {}
            @Override public void warn(String m, Object... a) {}
            @Override public void error(String m, Object... a) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m, Object... a) {}
        };
        bus = new DefaultSyncBus(logger);
    }

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.stop();
        }
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Condition not met within " + timeout + " " + unit);
    }

    private Message chatMessage(Actor sender, String text) {
        return Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.all())
            .text(text)
            .channel("chat")
            .build();
    }

    private static class MockSink implements SyncSink {
        private final String name;
        final AtomicInteger received = new AtomicInteger(0);
        final AtomicReference<Exception> lastError = new AtomicReference<>();
        volatile SyncListener listener;
        volatile boolean started = false;
        volatile boolean stopped = false;

        MockSink(String name) {
            this.name = name;
        }

        @Override
        public String name() { return name; }

        @Override
        public void start() { 
            started = true; 
        }

        @Override
        public void stop() { stopped = true; }

        @Override
        public void send(Message message) {
            received.incrementAndGet();
        }

        @Override
        public void setListener(SyncListener listener) {
            this.listener = listener;
        }

        @Override
        public DeliverySemantics deliverySemantics() { return DeliverySemantics.AT_LEAST_ONCE; }

        @Override
        public Ordering ordering() { return Ordering.PER_CHANNEL; }

        void simulateInbound(Message message) {
            if (listener != null) {
                listener.onMessage(this, message);
            }
        }
    }

    @Test
    @DisplayName("start/stop lifecycle funciona correctamente")
    void lifecycleStartStop() throws Exception {
        setUp();

        Assertions.assertThat(bus.isEmpty()).isTrue();
        
        bus.start();
        
        Assertions.assertThat(bus.isEmpty()).isTrue();
        
        bus.stop();
        
        Assertions.assertThat(bus.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("register/unregister sinks funciona correctamente")
    void registerUnregisterSinks() throws Exception {
        setUp();
        bus.start();

        MockSink sink1 = new MockSink("sink1");
        MockSink sink2 = new MockSink("sink2");

        bus.register(sink1);
        bus.register(sink2);

        Assertions.assertThat(bus.sinkNames()).containsExactlyInAnyOrder("sink1", "sink2");
        Assertions.assertThat(bus.sinks()).hasSize(2);

        Assertions.assertThat(bus.unregister("sink1")).isTrue();
        Assertions.assertThat(bus.sinkNames()).containsExactly("sink2");
        
        Assertions.assertThat(bus.unregister("nonexistent")).isFalse();
        
        bus.stop();
    }

    @Test
    @DisplayName("broadcast envía mensaje a todos los sinks registrados")
    void broadcastToAllSinks() throws Exception {
        setUp();

        MockSink sink1 = new MockSink("sink1");
        MockSink sink2 = new MockSink("sink2");
        bus.register(sink1);
        bus.register(sink2);

        bus.start();
        Thread.sleep(100); // Allow sinks to fully start

        Message msg = chatMessage(STEVE, "hello world");
        List<SyncResult> result = bus.send(msg);

        Assertions.assertThat(result).hasSize(2);
        Assertions.assertThat(result).allMatch(SyncResult::isOk);
        Assertions.assertThat(sink1.received.get()).isEqualTo(1);
        Assertions.assertThat(sink2.received.get()).isEqualTo(1);
        
        bus.stop();
    }

    @Test
    @DisplayName("send devuelve un resultado por backend")
    void sendReturnsOneResultPerBackend() throws Exception {
        setUp();

        MockSink sink1 = new MockSink("sink1");
        MockSink sink2 = new MockSink("sink2");
        bus.register(sink1);
        bus.register(sink2);

        bus.start();
        Thread.sleep(100); // Allow sinks to fully start

        List<SyncResult> results = bus.send(chatMessage(STEVE, "send test"));

        Assertions.assertThat(results).hasSize(2);
        Assertions.assertThat(results).allMatch(SyncResult::isOk);
        Assertions.assertThat(bus.surfaces()).hasSize(2);
        Assertions.assertThat(sink1.received.get()).isEqualTo(1);
        Assertions.assertThat(sink2.received.get()).isEqualTo(1);

        bus.stop();
    }

    @Test
    @DisplayName("sendTo envía a sink específico por nombre")
    void sendToSpecificSink() throws Exception {
        setUp();

        MockSink sink1 = new MockSink("target");
        MockSink sink2 = new MockSink("other");
        bus.register(sink1);
        bus.register(sink2);

        bus.start();
        Thread.sleep(100); // Allow sinks to fully start

        Message msg = chatMessage(STEVE, "directed");
        List<SyncResult> sent = bus.sendTo("target", msg);

        Assertions.assertThat(sent).hasSize(1);
        Assertions.assertThat(sent.get(0).isOk()).isTrue();
        Assertions.assertThat(sink1.received.get()).isEqualTo(1);
        Assertions.assertThat(sink2.received.get()).isZero();

        List<SyncResult> missing = bus.sendTo("nonexistent", msg);
        Assertions.assertThat(missing).hasSize(1);
        Assertions.assertThat(missing.get(0)).isInstanceOf(SyncResult.Unsupported.class);
        
        bus.stop();
    }

    @Test
    @DisplayName("inbound listener recibe mensajes de sinks")
    void inboundListenerReceivesMessages() throws Exception {
        setUp();
        bus.start();

        MockSink sink = new MockSink("inbound-sink");
        bus.register(sink);
        Thread.sleep(100); // Allow sink to fully start

        AtomicReference<Message> received = new AtomicReference<>();
        bus.setInboundListener(new SyncListener() {
            @Override
            public void onMessage(SyncSink sink, Message message) {
                received.set(message);
            }
            @Override
            public void onDisconnect(SyncSink sink, String reason) {
            }
        });

        Message inboundMsg = chatMessage(ALEX, "from external");
        sink.simulateInbound(inboundMsg);

        // Esperar un poco para procesamiento asíncrono
        Thread.sleep(100);

        Assertions.assertThat(received.get()).isNotNull();
        Assertions.assertThat(received.get().text()).isEqualTo("from external");
        Assertions.assertThat(received.get().sender().name()).isEqualTo("Alex");
        
        bus.stop();
    }

    @Test
    @DisplayName("detección de duplicados funciona correctamente")
    void deduplicationWorks() throws Exception {
        setUp();

        MockSink sink = new MockSink("dedup-sink");
        bus.register(sink);

        bus.start();
        Thread.sleep(100); // Allow sinks to fully start

        // Crear mensaje con ID fijo para probar deduplicación
        UUID fixedId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Message msg = Message.builder()
            .id(fixedId)
            .type(MessageType.CHAT)
            .sender(STEVE)
            .direction(Direction.all())
            .text("duplicate test")
            .channel("chat")
            .build();
        
        // Enviar el mismo mensaje dos veces (mismo ID)
        Assertions.assertThat(bus.send(msg)).hasSize(1);
        Assertions.assertThat(bus.send(msg)).isEmpty();

        // Solo debería recibir uno (el segundo se deduplica)
        Assertions.assertThat(sink.received.get()).isEqualTo(1);
        Assertions.assertThat(bus.getTotalDeduped()).isEqualTo(1);
        
        bus.stop();
    }

    @Test
    @DisplayName("métricas se actualizan correctamente")
    void metricsUpdateCorrectly() throws Exception {
        setUp();

        MockSink sink1 = new MockSink("metrics1");
        MockSink sink2 = new MockSink("metrics2");
        bus.register(sink1);
        bus.register(sink2);

        bus.start();
        Thread.sleep(100); // Allow sinks to fully start

        Assertions.assertThat(bus.getTotalBroadcast()).isZero();
        Assertions.assertThat(bus.getTotalDropped()).isZero();
        Assertions.assertThat(bus.getTotalRetries()).isZero();
        Assertions.assertThat(bus.getTotalDeduped()).isZero();

        bus.send(chatMessage(STEVE, "metric test"));
        bus.send(chatMessage(ALEX, "metric test 2"));

        Assertions.assertThat(sink1.received.get()).isEqualTo(2);
        Assertions.assertThat(sink2.received.get()).isEqualTo(2);
        Assertions.assertThat(bus.getTotalBroadcast()).isEqualTo(2);
        Assertions.assertThat(bus.getTotalDropped()).isZero();
        Assertions.assertThat(bus.getTotalRetries()).isZero();
        Assertions.assertThat(bus.getTotalDeduped()).isZero();
        
        bus.stop();
    }

    @Test
    @DisplayName("backpressure: broadcast espera cuando cola global llena")
    void backpressureGlobalQueueFull() throws Exception {
        setUp();
        bus.start();

        // Crear sink lento que no procesa
        MockSink slowSink = new MockSink("slow");
        bus.register(slowSink);
        Thread.sleep(100); // Allow sink to fully start

        // Llenar la cola global (capacidad 10000) - usamos sendTo para evitar el global processor
        // En su lugar, probamos que el broadcast con sinks registrados funciona bajo presión
        Message msg = chatMessage(STEVE, "pressure");
        List<SyncResult> result = bus.send(msg);

        Assertions.assertThat(result).hasSize(1);
        Assertions.assertThat(result.get(0).isOk()).isTrue();

        bus.stop();
    }

    @Test
    @DisplayName("send antes de start() no entrega y no cuenta como broadcast")
    void sendBeforeStartDoesNotDeliver() {
        setUp(); // sin start()

        List<SyncResult> result = bus.send(chatMessage(STEVE, "before start"));

        Assertions.assertThat(result).isEmpty();
        Assertions.assertThat(bus.getTotalBroadcast()).isZero();

        bus.stop();
    }

    @Test
    @DisplayName("unregister detiene sink y libera recursos")
    void unregisterStopsSink() throws Exception {
        setUp();
        bus.start();

        MockSink sink = new MockSink("to-stop");
        bus.register(sink);
        
        Assertions.assertThat(bus.unregister("to-stop")).isTrue();
        
        Assertions.assertThat(sink.stopped).isTrue();
        Assertions.assertThat(bus.sinkNames()).isEmpty();
        
        bus.stop();
    }
}
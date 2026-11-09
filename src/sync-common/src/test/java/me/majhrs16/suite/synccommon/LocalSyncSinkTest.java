package me.majhrs16.suite.synccommon;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.DeliveryEnvelope;
import me.majhrs16.suite.api.pipeline.DeliverySummary;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.LocalDelivery;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSyncSinkTest {

    private static final Principal OFFICIAL = new Principal.Official("suite.test");

    @Test
    void publishesItsBackendAndTheLeastCommonMultiple() {
        LocalSyncSink sink = new LocalSyncSink("bukkit", new FakeRegistry());

        assertEquals("bukkit", sink.name());
        assertEquals("bukkit", sink.surface().backend());
        assertTrue(sink.surface().hasLeastCommonMultiple(),
                "the local sink must publish the full LCM");
        assertEquals(SyncSink.DeliverySemantics.EXACTLY_ONCE, sink.deliverySemantics());
        assertEquals(SyncSink.Ordering.GLOBAL, sink.ordering());
    }

    @Test
    void deliversThroughTheLocalPortAndReturnsItsSummary() {
        FakeRegistry registry = new FakeRegistry();
        DeliverySummary summary = new DeliverySummary(2, 1, 0, 0);
        DeliveryEnvelope envelope = DeliveryEnvelope.empty(
                Message.builder().text("hello").build());
        registry.add(LocalDelivery.class, actual -> {
            assertSame(envelope, actual);
            return summary;
        });
        LocalSyncSink sink = new LocalSyncSink("bukkit", registry);

        SyncResult result = sink.deliver(envelope);

        assertTrue(result instanceof SyncResult.Ok, "expected Ok, got: " + result);
        assertSame(summary, ((SyncResult.Ok) result).value());
    }

    @Test
    void reportsAnUnregisteredPortExplicitly() {
        LocalSyncSink sink = new LocalSyncSink("fabric", new FakeRegistry());

        SyncResult result = sink.deliver(DeliveryEnvelope.empty(
                Message.builder().text("hello").build()));

        assertTrue(result instanceof SyncResult.HostFailure,
                "a missing local port must fail closed, got: " + result);
        assertTrue(((SyncResult.HostFailure) result).reason().contains("LocalDelivery"));
    }

    @Test
    void turnsPortFailuresIntoHostFailures() {
        FakeRegistry registry = new FakeRegistry();
        registry.add(LocalDelivery.class, envelope -> {
            throw new IllegalStateException("platform unavailable");
        });
        LocalSyncSink sink = new LocalSyncSink("bukkit", registry);

        SyncResult result = sink.deliver(DeliveryEnvelope.empty(
                Message.builder().text("hello").build()));

        assertTrue(result instanceof SyncResult.HostFailure, "got: " + result);
        String reason = ((SyncResult.HostFailure) result).reason();
        assertTrue(reason.contains("IllegalStateException"), reason);
        assertTrue(reason.contains("platform unavailable"), reason);
    }

    @Test
    void rejectsMessageSendBecauseTheLocalPathNeedsTheEnvelope() {
        LocalSyncSink sink = new LocalSyncSink("bukkit", new FakeRegistry());
        Message message = Message.builder().text("hello").build();

        SyncResult result = sink.invoke(new SyncCall(
                "bukkit", SyncSurface.MESSAGE_SEND, Map.of("message", message),
                OFFICIAL, "", "direct send"));

        assertTrue(result instanceof SyncResult.Unsupported, "got: " + result);
        assertTrue(((SyncResult.Unsupported) result).reason().contains("message.deliver"));
    }

    private static final class FakeRegistry implements ServiceRegistry {

        private final Map<Class<?>, Object> services = new java.util.HashMap<>();

        <T> void add(Class<T> type, T service) {
            services.put(type, service);
        }

        @Override
        public <T> Registration register(Class<T> type, T service) {
            add(type, service);
            return () -> services.remove(type);
        }

        @Override
        public <T> Optional<T> find(Class<T> type) {
            return Optional.ofNullable(type.cast(services.get(type)));
        }

        @Override
        public <T> List<T> findAll(Class<T> type) {
            return find(type).map(List::of).orElseGet(List::of);
        }
    }
}

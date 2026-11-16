package me.majhrs16.suite.synccommon;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.DeliveryEnvelope;
import me.majhrs16.suite.api.pipeline.DeliverySummary;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.LocalDelivery;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncActionSpec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSyncSinkTest {

    private static final Principal OFFICIAL = new Principal.Official("suite.test");
    private static final Principal PROGRAM =
            new Principal.Program("0".repeat(64));

    @Test
    void publishesItsBackendAndTheLeastCommonMultiple() {
        LocalSyncSink sink = sink("bukkit", new FakeRegistry());

        assertEquals("bukkit", sink.name());
        assertEquals("bukkit", sink.surface().backend());
        assertTrue(sink.surface().hasLeastCommonMultiple(),
                "the local sink must publish the full LCM");
        assertEquals(SyncSink.DeliverySemantics.EXACTLY_ONCE, sink.deliverySemantics());
        assertEquals(SyncSink.Ordering.GLOBAL, sink.ordering());
    }

    @Test
    void publishesLocalInfoAsAReadOnlyIdempotentAction() {
        LocalSyncSink sink = sink("fabric", new FakeRegistry());

        SyncActionSpec info = sink.surface().action("local.info").orElseThrow();

        assertEquals(ActionKind.READ, info.kind());
        assertTrue(info.idempotent(), "local.info must be safe to retry");
        assertEquals(5_000, info.timeoutMillis());
        assertTrue(info.params().isEmpty(), "local.info takes no arguments");
    }

    @Test
    void publishesTheEnvironmentSurfaceWithExplicitKinds() {
        LocalSyncSink sink = sink("bukkit", new FakeRegistry());

        assertEquals(ActionKind.READ,
                sink.surface().action("player.list").orElseThrow().kind());
        assertEquals(ActionKind.READ,
                sink.surface().action("player.info").orElseThrow().kind());
        assertEquals(ActionKind.WRITE,
                sink.surface().action("player.message").orElseThrow().kind());
        assertEquals(ActionKind.DESTRUCTIVE,
                sink.surface().action("player.kick").orElseThrow().kind());
        assertEquals(ActionKind.DESTRUCTIVE,
                sink.surface().action("console.command").orElseThrow().kind());
        assertEquals(ActionKind.WRITE,
                sink.surface().action("scoreboard.set").orElseThrow().kind());
        assertEquals(ActionKind.READ,
                sink.surface().action("event.info").orElseThrow().kind());
        assertEquals(ActionKind.WRITE,
                sink.surface().action("event.cancel").orElseThrow().kind());
    }

    @Test
    void reportsLocalStateWithoutExposingThePort() {
        LocalSyncSink sink = sink("bukkit", new FakeRegistry());

        SyncResult result = sink.invoke(call("bukkit", "local.info", PROGRAM));

        assertTrue(result instanceof SyncResult.Ok, "expected Ok, got: " + result);
        Map<?, ?> info = (Map<?, ?>) ((SyncResult.Ok) result).value();
        assertEquals("bukkit", info.get("name"));
        assertEquals(SyncSurface.CURRENT_CONTRACT_VERSION.toString(),
                info.get("surfaceVersion"));
        assertEquals(false, info.get("localDeliveryRegistered"));
        assertEquals("EXACTLY_ONCE", info.get("deliverySemantics"));
        assertEquals("GLOBAL", info.get("ordering"));
        assertEquals(true, info.get("environmentRegistered"));
        assertEquals(false, info.get("eventBound"));
    }

    @Test
    void reportsWhetherTheHostRegisteredTheLocalPort() {
        FakeRegistry registry = new FakeRegistry();
        registry.add(LocalDelivery.class, envelope -> new DeliverySummary(1, 0, 0, 0, 0, 0));
        LocalSyncSink sink = sink("fabric", registry);

        SyncResult result = sink.invoke(call("fabric", "local.info", PROGRAM));

        assertTrue(result instanceof SyncResult.Ok, "expected Ok, got: " + result);
        Map<?, ?> info = (Map<?, ?>) ((SyncResult.Ok) result).value();
        assertEquals(true, info.get("localDeliveryRegistered"));
        assertFalse(info.containsKey("delivery"),
                "local.info must not leak the port or the envelope");
    }

    @Test
    void deniesNativeActionsToOfficialCallers() {
        FakeEnvironment environment = new FakeEnvironment();
        LocalSyncSink sink = sink("bukkit", new FakeRegistry(), environment);

        for (String action : List.of("local.info", "player.list", "player.kick",
                "console.command", "scoreboard.set", "event.cancel")) {
            SyncResult result = sink.invoke(call("bukkit", action, OFFICIAL));
            assertTrue(result instanceof SyncResult.Denied,
                    action + " must be denied, got: " + result);
        }
        assertTrue(environment.calls.isEmpty(),
                "a denied action must not reach the platform");
        assertTrue(((SyncResult.Denied) sink.invoke(
                call("bukkit", "local.info", OFFICIAL))).reason()
                .contains("least common multiple"));
    }

    @Test
    void rejectsUnpublishedLocalActionsExplicitly() {
        LocalSyncSink sink = sink("bukkit", new FakeRegistry());

        SyncResult result = sink.invoke(call("bukkit", "local.purge", PROGRAM));

        assertTrue(result instanceof SyncResult.Unsupported, "got: " + result);
        assertTrue(((SyncResult.Unsupported) result).reason().contains("not published"));
    }

    @Test
    void rejectsCallsAddressedToAnotherBackend() {
        LocalSyncSink sink = sink("bukkit", new FakeRegistry());

        SyncResult result = sink.invoke(call("fabric", "local.info", PROGRAM));

        assertTrue(result instanceof SyncResult.Unsupported, "got: " + result);
        assertTrue(((SyncResult.Unsupported) result).reason().contains("not served"));
    }

    @Test
    void listsAndFindsOnlinePlayers() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.players.add(new LocalPlayer("uuid-1", "LUNA", "world"));
        LocalSyncSink sink = sink("bukkit", new FakeRegistry(), environment);

        SyncResult list = sink.invoke(call("bukkit", "player.list", PROGRAM));
        SyncResult.Ok ok = assertInstanceOf(SyncResult.Ok.class, list);
        assertEquals(List.of(Map.of(
                "uuid", "uuid-1", "name", "LUNA", "world", "world")),
                ((Map<?, ?>) ok.value()).get("players"));

        SyncResult info = sink.invoke(new SyncCall(
                "bukkit", "player.info", Map.of("player", "LUNA"),
                PROGRAM, "", "test"));
        SyncResult.Ok found = assertInstanceOf(SyncResult.Ok.class, info);
        assertEquals("LUNA", ((Map<?, ?>) found.value()).get("name"));

        SyncResult missing = sink.invoke(new SyncCall(
                "bukkit", "player.info", Map.of("player", "NOBODY"),
                PROGRAM, "", "test"));
        assertInstanceOf(SyncResult.NotFound.class, missing);
    }

    @Test
    void executesEveryEnvironmentMutationExplicitly() {
        FakeEnvironment environment = new FakeEnvironment();
        environment.event = new FakeEvent();
        LocalSyncSink sink = sink("bukkit", new FakeRegistry(), environment);

        assertInstanceOf(SyncResult.Ok.class, sink.invoke(new SyncCall(
                "bukkit", "player.message",
                Map.of("player", "LUNA", "text", "hola"),
                PROGRAM, "", "test")));
        assertInstanceOf(SyncResult.Ok.class, sink.invoke(new SyncCall(
                "bukkit", "player.kick",
                Map.of("player", "LUNA", "reason", "spam"),
                PROGRAM, "", "test")));
        assertInstanceOf(SyncResult.Ok.class, sink.invoke(new SyncCall(
                "bukkit", "console.command",
                Map.of("command", "say hola"),
                PROGRAM, "", "test")));
        SyncResult.Ok score = assertInstanceOf(SyncResult.Ok.class,
                sink.invoke(new SyncCall("bukkit", "scoreboard.set",
                        Map.of("objective", "points", "entry", "LUNA", "value", 7),
                        PROGRAM, "", "test")));
        assertEquals(7, ((Map<?, ?>) score.value()).get("value"));
        assertInstanceOf(SyncResult.Ok.class, sink.invoke(
                call("bukkit", "event.info", PROGRAM)));
        assertInstanceOf(SyncResult.Ok.class, sink.invoke(
                call("bukkit", "event.cancel", PROGRAM)));

        assertEquals(List.of(
                "player.message:LUNA:hola",
                "player.kick:LUNA:spam",
                "console.command:say hola",
                "scoreboard.set:points:LUNA:7"), environment.calls);
        assertTrue(((FakeEvent) environment.event).cancelled);
    }

    @Test
    void invalidArgumentsNeverReachTheEnvironment() {
        FakeEnvironment environment = new FakeEnvironment();
        LocalSyncSink sink = sink("bukkit", new FakeRegistry(), environment);

        assertInstanceOf(SyncResult.InvalidArgument.class, sink.invoke(new SyncCall(
                "bukkit", "player.info", Map.of("player", " "),
                PROGRAM, "", "test")));
        assertInstanceOf(SyncResult.InvalidArgument.class, sink.invoke(new SyncCall(
                "bukkit", "player.message", Map.of("player", "LUNA"),
                PROGRAM, "", "test")));
        assertInstanceOf(SyncResult.InvalidArgument.class, sink.invoke(new SyncCall(
                "bukkit", "console.command", Map.of("command", ""),
                PROGRAM, "", "test")));
        assertInstanceOf(SyncResult.InvalidArgument.class, sink.invoke(new SyncCall(
                "bukkit", "scoreboard.set",
                Map.of("objective", "points", "entry", "LUNA", "value", "seven"),
                PROGRAM, "", "test")));

        assertTrue(environment.calls.isEmpty());
    }

    @Test
    void missingEventIsNotFoundAndPlatformFailuresKeepTheirKind() {
        FakeEnvironment environment = new FakeEnvironment();
        LocalSyncSink sink = sink("bukkit", new FakeRegistry(), environment);

        assertInstanceOf(SyncResult.NotFound.class, sink.invoke(
                call("bukkit", "event.info", PROGRAM)));
        assertInstanceOf(SyncResult.NotFound.class, sink.invoke(
                call("bukkit", "event.cancel", PROGRAM)));

        environment.failure = new LocalActionException(
                LocalActionException.Kind.NOT_FOUND, "player is not online");
        assertInstanceOf(SyncResult.NotFound.class, sink.invoke(new SyncCall(
                "bukkit", "player.kick", Map.of("player", "LUNA"),
                PROGRAM, "", "test")));

        environment.failure = new LocalActionException(
                LocalActionException.Kind.UNSUPPORTED, "event cannot be cancelled");
        assertInstanceOf(SyncResult.Unsupported.class, sink.invoke(
                call("bukkit", "event.cancel", PROGRAM)));

        environment.failure = new LocalActionException(
                LocalActionException.Kind.INVALID_ARGUMENT, "bad score");
        assertInstanceOf(SyncResult.InvalidArgument.class, sink.invoke(new SyncCall(
                "bukkit", "scoreboard.set",
                Map.of("objective", "points", "entry", "LUNA", "value", 1),
                PROGRAM, "", "test")));

        environment.failure = new LocalActionException(
                LocalActionException.Kind.HOST_FAILURE, "platform down");
        SyncResult.HostFailure failure = assertInstanceOf(SyncResult.HostFailure.class,
                sink.invoke(new SyncCall("bukkit", "console.command",
                        Map.of("command", "say hola"), PROGRAM, "", "test")));
        assertTrue(failure.reason().contains("platform down"));
    }

    @Test
    void deliversThroughTheLocalPortAndReturnsItsSummary() {
        FakeRegistry registry = new FakeRegistry();
        DeliverySummary summary = new DeliverySummary(2, 1, 0, 0, 0, 0);
        DeliveryEnvelope envelope = DeliveryEnvelope.empty(
                Message.builder().text("hello").build());
        registry.add(LocalDelivery.class, actual -> {
            assertSame(envelope, actual);
            return summary;
        });
        LocalSyncSink sink = sink("bukkit", registry);

        SyncResult result = sink.deliver(envelope);

        assertTrue(result instanceof SyncResult.Ok, "expected Ok, got: " + result);
        assertSame(summary, ((SyncResult.Ok) result).value());
    }

    @Test
    void reportsAnUnregisteredPortExplicitly() {
        LocalSyncSink sink = sink("fabric", new FakeRegistry());

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
        LocalSyncSink sink = sink("bukkit", registry);

        SyncResult result = sink.deliver(DeliveryEnvelope.empty(
                Message.builder().text("hello").build()));

        assertTrue(result instanceof SyncResult.HostFailure, "got: " + result);
        String reason = ((SyncResult.HostFailure) result).reason();
        assertTrue(reason.contains("IllegalStateException"), reason);
        assertTrue(reason.contains("platform unavailable"), reason);
    }

    @Test
    void rejectsMessageSendBecauseTheLocalPathNeedsTheEnvelope() {
        LocalSyncSink sink = sink("bukkit", new FakeRegistry());
        Message message = Message.builder().text("hello").build();

        SyncResult result = sink.invoke(new SyncCall(
                "bukkit", SyncSurface.MESSAGE_SEND, Map.of("message", message),
                OFFICIAL, "", "direct send"));

        assertTrue(result instanceof SyncResult.Unsupported, "got: " + result);
        assertTrue(((SyncResult.Unsupported) result).reason().contains("message.deliver"));
    }

    @Test
    void executesMessageDeliverThroughThePublishedSurface() {
        FakeRegistry registry = new FakeRegistry();
        DeliverySummary summary = new DeliverySummary(1, 0, 0, 0, 0, 0);
        registry.add(LocalDelivery.class, envelope -> summary);
        LocalSyncSink sink = sink("bukkit", registry);
        DeliveryEnvelope envelope = DeliveryEnvelope.empty(
                Message.builder().text("hello").build());

        SyncResult result = sink.invoke(new SyncCall(
                "bukkit", SyncSurface.MESSAGE_DELIVER, Map.of("delivery", envelope),
                OFFICIAL, "", "pipeline delivery"));

        assertTrue(result instanceof SyncResult.Ok, "got: " + result);
        assertSame(summary, ((SyncResult.Ok) result).value());
    }

    private static LocalSyncSink sink(String backend, FakeRegistry registry) {
        return sink(backend, registry, new FakeEnvironment());
    }

    private static LocalSyncSink sink(String backend, FakeRegistry registry,
                                      LocalEnvironment environment) {
        return new LocalSyncSink(backend, registry, environment);
    }

    private static SyncCall call(String backend, String action, Principal principal) {
        return new SyncCall(backend, action, Map.of(), principal, "", "test");
    }

    private static final class FakeEnvironment implements LocalEnvironment {

        final List<LocalPlayer> players = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        LocalEvent event;
        LocalActionException failure;

        private void failIfConfigured() {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public List<LocalPlayer> players() {
            failIfConfigured();
            return List.copyOf(players);
        }

        @Override
        public Optional<LocalPlayer> player(String reference) {
            failIfConfigured();
            return players.stream()
                    .filter(player -> player.name().equals(reference)
                            || player.uuid().equals(reference))
                    .findFirst();
        }

        @Override
        public void sendMessage(String reference, String text) {
            failIfConfigured();
            calls.add("player.message:" + reference + ":" + text);
        }

        @Override
        public void kick(String reference, String reason) {
            failIfConfigured();
            calls.add("player.kick:" + reference + ":" + reason);
        }

        @Override
        public void runConsoleCommand(String command) {
            failIfConfigured();
            calls.add("console.command:" + command);
        }

        @Override
        public void setScore(String objective, String entry, int value) {
            failIfConfigured();
            calls.add("scoreboard.set:" + objective + ":" + entry + ":" + value);
        }

        @Override
        public Optional<LocalEvent> currentEvent() {
            failIfConfigured();
            return Optional.ofNullable(event);
        }
    }

    private static final class FakeEvent implements LocalEvent {

        boolean cancelled;

        @Override
        public Map<String, Object> info() {
            return Map.of("type", "Fake");
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
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

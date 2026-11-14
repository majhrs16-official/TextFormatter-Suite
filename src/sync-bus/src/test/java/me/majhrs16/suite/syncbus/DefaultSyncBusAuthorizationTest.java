package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.ActionAudit;
import me.majhrs16.suite.api.sync.ActionAuditRecord;
import me.majhrs16.suite.api.sync.ActionKind;
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
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSyncBusAuthorizationTest {

    private static final String SHA = "b".repeat(64);
    private static final Principal PROGRAM = new Principal.Program(SHA);
    private static final Principal OFFICIAL = new Principal.Official("suite.test");

    private final PluginLogger logger = new NoopLogger();

    @Test
    void missingGrantDeniesTheProgram() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(), audit);
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(call("channel.create", Map.of("name", "general")));

            assertTrue(result instanceof SyncResult.Denied, result.toString());
            assertEquals(0, sink.calls.get(), "a program without a grant must not reach the sink");
            assertEquals(SyncBridgeCodec.STATUS_DENIED, audit.records.get(0).status());
        } finally {
            bus.stop();
        }
    }

    @Test
    void grantWithoutTheCapabilityDeniesTheAction() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant(Set.of("sync.discord.message.send"))), audit);
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(call("channel.create", Map.of("name", "general")));

            assertTrue(result instanceof SyncResult.Denied, result.toString());
            assertEquals(0, sink.calls.get());
        } finally {
            bus.stop();
        }
    }

    @Test
    void exactCapabilityAuthorizesTheAction() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok("created"));
        DefaultSyncBus bus = bus(Map.of(SHA, grant(Set.of("sync.discord.channel.create"))), audit);
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(call("channel.create", Map.of("name", "general")));

            assertTrue(result.isOk(), result.toString());
            assertEquals(1, sink.calls.get());
            ActionAuditRecord record = audit.records.get(0);
            assertEquals(SHA, record.program());
            assertEquals("program:" + SHA, record.actor());
            assertEquals("discord", record.backend());
            assertEquals("channel.create", record.action());
            assertEquals(SyncBridgeCodec.STATUS_OK, record.status());
            assertTrue(record.argsHash().matches("[0-9a-f]{64}"), record.argsHash());
        } finally {
            bus.stop();
        }
    }

    @Test
    void trailingWildcardAuthorizesNestedActions() throws Exception {
        ProgramGrant grant = new ProgramGrant(SHA, Set.of("sync.discord.*"),
                true, 60, 65_536);
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant), new RecordingAudit());
        bus.register(sink);
        bus.start();
        try {
            assertTrue(bus.invoke(call("channel.create", Map.of("name", "general"))).isOk());
            assertTrue(bus.invoke(call("channel.delete", Map.of("id", "1"))).isOk());
            assertEquals(2, sink.calls.get());
        } finally {
            bus.stop();
        }
    }

    @Test
    void destructiveActionRequiresAllowDestructive() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant(Set.of("sync.discord.*"))), audit);
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(call("channel.delete", Map.of("id", "1")));

            assertTrue(result instanceof SyncResult.Denied, result.toString());
            assertEquals(0, sink.calls.get(),
                    "a destructive action without allow-destructive must not run");
            assertTrue(audit.records.get(0).detail().contains("allow-destructive"));
        } finally {
            bus.stop();
        }
    }

    @Test
    void moderationActionRequiresAllowDestructive() throws Exception {
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant(Set.of("sync.discord.*"))), new RecordingAudit());
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(call("member.ban", Map.of("id", "1")));

            assertTrue(result instanceof SyncResult.Denied, result.toString());
            assertEquals(0, sink.calls.get());
        } finally {
            bus.stop();
        }
    }

    @Test
    void allowDestructiveAuthorizesDestructiveAndModeration() throws Exception {
        ProgramGrant grant = new ProgramGrant(SHA, Set.of("sync.discord.*"), true, 60, 65_536);
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant), new RecordingAudit());
        bus.register(sink);
        bus.start();
        try {
            assertTrue(bus.invoke(call("channel.delete", Map.of("id", "1"))).isOk());
            assertTrue(bus.invoke(call("member.ban", Map.of("id", "2"))).isOk());
            assertEquals(2, sink.calls.get());
        } finally {
            bus.stop();
        }
    }

    @Test
    void callsPerMinuteIsEnforced() throws Exception {
        ProgramGrant grant = new ProgramGrant(SHA, Set.of("sync.discord.channel.create"),
                false, 2, 65_536);
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant), new RecordingAudit());
        bus.register(sink);
        bus.start();
        try {
            assertTrue(bus.invoke(call("channel.create", Map.of("name", "one"))).isOk());
            assertTrue(bus.invoke(call("channel.create", Map.of("name", "two"))).isOk());

            SyncResult limited = bus.invoke(call("channel.create", Map.of("name", "three")));

            assertTrue(limited instanceof SyncResult.RateLimited, limited.toString());
            assertTrue(((SyncResult.RateLimited) limited).retryAfterMillis() > 0);
            assertEquals(2, sink.calls.get(), "the exhausted call must not reach the sink");
        } finally {
            bus.stop();
        }
    }

    @Test
    void theCallBudgetIsASlidingWindowAndCannotBeDoubledAtTheBoundary() throws Exception {
        ProgramGrant grant = new ProgramGrant(SHA, Set.of("sync.discord.channel.create"),
                false, 2, 65_536);
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant), new RecordingAudit());
        AtomicLong now = new AtomicLong();
        bus.useClock(now::get);
        bus.register(sink);
        bus.start();
        try {
            assertTrue(bus.invoke(call("channel.create", Map.of("name", "one"))).isOk());
            now.set(TimeUnit.SECONDS.toNanos(59));
            assertTrue(bus.invoke(call("channel.create", Map.of("name", "two"))).isOk());

            now.set(TimeUnit.SECONDS.toNanos(59) + TimeUnit.MILLISECONDS.toNanos(500));
            SyncResult limited = bus.invoke(call("channel.create", Map.of("name", "three")));
            assertTrue(limited instanceof SyncResult.RateLimited, limited.toString());
            long retry = ((SyncResult.RateLimited) limited).retryAfterMillis();
            assertTrue(retry > 0 && retry <= 500, "retryAfter=" + retry);

            // The first call expires at t=60s and the second only at t=119s. A
            // fixed window would refund the whole budget at t=60s and let both
            // remaining attempts through.
            now.set(TimeUnit.SECONDS.toNanos(60) + TimeUnit.MILLISECONDS.toNanos(500));
            assertTrue(bus.invoke(call("channel.create", Map.of("name", "four"))).isOk());
            now.set(TimeUnit.SECONDS.toNanos(61));
            SyncResult stillLimited = bus.invoke(call("channel.create", Map.of("name", "five")));
            assertTrue(stillLimited instanceof SyncResult.RateLimited, stillLimited.toString());
            assertEquals(3, sink.calls.get());
        } finally {
            bus.stop();
        }
    }

    @Test
    void payloadOverTheLimitIsDeniedNotRateLimited() throws Exception {
        ProgramGrant grant = new ProgramGrant(SHA, Set.of("sync.discord.channel.create"),
                false, 60, 8);
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant), new RecordingAudit());
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(call("channel.create", Map.of("name", "a-long-name")));

            assertTrue(result instanceof SyncResult.Denied, result.toString());
            assertTrue(((SyncResult.Denied) result).reason().contains("max-payload-bytes"));
            assertEquals(0, sink.calls.get(),
                    "an oversized payload must never reach the sink");
        } finally {
            bus.stop();
        }
    }

    @Test
    void auditRedactsSecretValuesBeforeHashing() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant(Set.of("sync.discord.channel.create"))), audit);
        bus.register(sink);
        bus.start();
        try {
            assertTrue(bus.invoke(call("channel.create",
                    Map.of("name", "visible", "token", "super-secret"))).isOk());
            assertTrue(bus.invoke(call("channel.create",
                    Map.of("name", "visible", "token", "another-secret"))).isOk());

            ActionAuditRecord first = audit.records.get(0);
            ActionAuditRecord second = audit.records.get(1);
            assertEquals(first.argsHash(), second.argsHash(),
                    "the secret value must not influence the digest");
            assertFalse(first.toString().contains("super-secret"));
            assertFalse(first.toString().contains("another-secret"));
            assertFalse(first.toString().contains("visible"));
        } finally {
            bus.stop();
        }
    }

    @Test
    void undeclaredArgumentsAreTreatedAsSecret() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant(Set.of("sync.discord.channel.create"))), audit);
        bus.register(sink);
        bus.start();
        try {
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("name", "visible");
            first.put("extra", "one");
            Map<String, Object> second = new LinkedHashMap<>();
            second.put("name", "visible");
            second.put("extra", "two");

            assertTrue(bus.invoke(call("channel.create", first))
                    instanceof SyncResult.InvalidArgument);
            assertTrue(bus.invoke(call("channel.create", second))
                    instanceof SyncResult.InvalidArgument);

            assertEquals(audit.records.get(0).argsHash(), audit.records.get(1).argsHash(),
                    "an undeclared argument value must not reach the digest");
            assertEquals(SyncBridgeCodec.STATUS_INVALID_ARGUMENT,
                    audit.records.get(0).status());
        } finally {
            bus.stop();
        }
    }

    @Test
    void differentVisibleArgumentsProduceDifferentDigests() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant(Set.of("sync.discord.channel.create"))), audit);
        bus.register(sink);
        bus.start();
        try {
            assertTrue(bus.invoke(call("channel.create", Map.of("name", "one"))).isOk());
            assertTrue(bus.invoke(call("channel.create", Map.of("name", "two"))).isOk());

            assertNotEquals(audit.records.get(0).argsHash(),
                    audit.records.get(1).argsHash());
        } finally {
            bus.stop();
        }
    }

    @Test
    void programIsDeniedWhenTheAuditTrailIsMissing() throws Exception {
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = new DefaultSyncBus(logger,
                () -> store(Map.of(SHA, grant(Set.of("sync.discord.channel.create")))),
                () -> null);
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(call("channel.create", Map.of("name", "general")));

            assertTrue(result instanceof SyncResult.Denied, result.toString());
            assertEquals(0, sink.calls.get(),
                    "a program action without an audit trail must not run");
        } finally {
            bus.stop();
        }
    }

    @Test
    void programIsDeniedWhenTheAuditTrailIsNotWritable() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        audit.writable = false;
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = bus(Map.of(SHA, grant(Set.of("sync.discord.channel.create"))), audit);
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(call("channel.create", Map.of("name", "general")));

            assertTrue(result instanceof SyncResult.Denied, result.toString());
            assertEquals(0, sink.calls.get());
        } finally {
            bus.stop();
        }
    }

    @Test
    void failingGrantStoreIsDenied() throws Exception {
        ProgramGrantStore failing = sha256 -> {
            throw new IllegalStateException("cannot read grants.yml");
        };
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        DefaultSyncBus bus = new DefaultSyncBus(logger, () -> failing, RecordingAudit::new);
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(call("channel.create", Map.of("name", "general")));

            assertTrue(result instanceof SyncResult.Denied, result.toString());
            assertTrue(((SyncResult.Denied) result).reason().contains("grant store"));
            assertEquals(0, sink.calls.get());
        } finally {
            bus.stop();
        }
    }

    @Test
    void hostActionUsesTheModuleCapability() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RecordingHostHandler handler = new RecordingHostHandler();
        DefaultSyncBus bus = bus(Map.of(SHA,
                grant(Set.of("module.textformatter.channel.set"))), audit);
        bus.discoverSinks(new HostRegistry(handler));
        bus.start();
        try {
            SyncResult result = bus.invokeHost(hostCall(PROGRAM, "channel.set"), hostContext());

            assertTrue(result.isOk(), result.toString());
            assertEquals(1, handler.calls.get());
            assertEquals("textformatter", audit.records.get(0).backend());
            assertEquals("channel.set", audit.records.get(0).action());
        } finally {
            bus.stop();
        }
    }

    @Test
    void hostActionRejectsTheSyncCapability() throws Exception {
        RecordingHostHandler handler = new RecordingHostHandler();
        DefaultSyncBus bus = bus(Map.of(SHA,
                grant(Set.of("sync.textformatter.channel.set"))), new RecordingAudit());
        bus.discoverSinks(new HostRegistry(handler));
        bus.start();
        try {
            SyncResult result = bus.invokeHost(hostCall(PROGRAM, "channel.set"), hostContext());

            assertTrue(result instanceof SyncResult.Denied, result.toString());
            assertEquals(0, handler.calls.get());
        } finally {
            bus.stop();
        }
    }

    @Test
    void officialHostActionIsDeniedAndAudited() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RecordingHostHandler handler = new RecordingHostHandler();
        DefaultSyncBus bus = bus(Map.of(), audit);
        bus.discoverSinks(new HostRegistry(handler));
        bus.start();
        try {
            SyncResult result = bus.invokeHost(hostCall(OFFICIAL, "channel.set"), hostContext());

            assertTrue(result instanceof SyncResult.Denied, result.toString());
            assertEquals(0, handler.calls.get());
            assertEquals(ActionAuditRecord.NO_PROGRAM, audit.records.get(0).program());
            assertEquals("module:suite.test", audit.records.get(0).actor());
            assertEquals(SyncBridgeCodec.STATUS_DENIED, audit.records.get(0).status());
        } finally {
            bus.stop();
        }
    }

    @Test
    void auditFailureAfterExecutionDoesNotChangeAnOfficialResult() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        audit.failRecord = true;
        DefaultSyncBus bus = bus(Map.of(), audit);
        SurfaceSink sink = new SurfaceSink("discord", call -> new SyncResult.Ok(null));
        bus.register(sink);
        bus.start();
        try {
            SyncResult result = bus.invoke(new SyncCall(
                    "discord", SyncSurface.BACKEND_INFO, Map.of(), OFFICIAL, "", "metadata"));

            assertTrue(result.isOk(),
                    "the official pipeline must not fail because the audit append failed");
        } finally {
            bus.stop();
        }
    }

    private DefaultSyncBus bus(Map<String, ProgramGrant> grants, RecordingAudit audit) {
        return new DefaultSyncBus(logger, () -> store(grants), () -> audit);
    }

    private static ProgramGrantStore store(Map<String, ProgramGrant> grants) {
        return sha256 -> Optional.ofNullable(grants.get(sha256));
    }

    private static ProgramGrant grant(Set<String> capabilities) {
        return new ProgramGrant(SHA, capabilities, false, 60, 65_536);
    }

    private static SyncCall call(String action, Map<String, Object> args) {
        return new SyncCall("discord", action, args, PROGRAM, "key", "test");
    }

    private static SyncCall hostCall(Principal principal, String action) {
        return new SyncCall("textformatter", action, Map.of("channel", "chat"),
                principal, "key", "test");
    }

    private static HostActionContext hostContext() {
        return new HostActionContext(
                Message.builder().text("hello").build(),
                Actor.unknown("Alice"),
                Actor.unknown("Bob"),
                ChannelRegistry.empty(),
                PermissionChecker.ALLOW_ALL);
    }

    private static SyncSurface discordSurface(String backend) {
        SyncSurface lcm = SyncSurface.lcm(backend, SemVer.of(1, 0, 0));
        Set<SyncActionSpec> actions = new LinkedHashSet<>(lcm.actions());
        actions.add(new SyncActionSpec(
                "channel.create",
                ActionKind.WRITE,
                List.of(
                        SyncParamSpec.required("name", SyncValueType.STRING),
                        SyncParamSpec.secret("token", SyncValueType.STRING, false)),
                null,
                false,
                1_000));
        actions.add(new SyncActionSpec(
                "channel.delete",
                ActionKind.DESTRUCTIVE,
                List.of(SyncParamSpec.required("id", SyncValueType.STRING)),
                null,
                false,
                1_000));
        actions.add(new SyncActionSpec(
                "member.ban",
                ActionKind.MODERATION,
                List.of(SyncParamSpec.required("id", SyncValueType.STRING)),
                null,
                false,
                1_000));
        return new SyncSurface(backend, lcm.contractVersion(), actions);
    }

    private static final class SurfaceSink implements SyncSink {
        private final String name;
        private final SyncSurface contract;
        private final Function<SyncCall, SyncResult> handler;
        private final AtomicInteger calls = new AtomicInteger();

        SurfaceSink(String name, Function<SyncCall, SyncResult> handler) {
            this.name = name;
            this.contract = discordSurface(name);
            this.handler = handler;
        }

        @Override public String name() { return name; }
        @Override public SyncSurface surface() { return contract; }
        @Override public SyncResult invoke(SyncCall call) {
            calls.incrementAndGet();
            return handler.apply(call);
        }
        @Override public DeliverySemantics deliverySemantics() {
            return DeliverySemantics.AT_LEAST_ONCE;
        }
        @Override public Ordering ordering() { return Ordering.PER_CHANNEL; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public void send(Message message) {}
        @Override public void setListener(SyncListener listener) {}
    }

    private static final class RecordingHostHandler implements HostActionHandler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public String backend() { return "textformatter"; }
        @Override public SyncActionSpec action() {
            return new SyncActionSpec(
                    "channel.set",
                    ActionKind.WRITE,
                    List.of(SyncParamSpec.required("channel", SyncValueType.CHANNEL_REF)),
                    null,
                    true,
                    1_000);
        }
        @Override public SyncResult invoke(SyncCall call, HostActionContext context) {
            calls.incrementAndGet();
            return new SyncResult.Ok(context.message());
        }
    }

    private static final class RecordingAudit implements ActionAudit {
        private final List<ActionAuditRecord> records = new CopyOnWriteArrayList<>();
        private volatile boolean writable = true;
        private volatile boolean failRecord;

        @Override public void ensureWritable() {
            if (!writable) {
                throw new IllegalStateException("audit log is read-only");
            }
        }

        @Override public void record(ActionAuditRecord record) {
            if (failRecord) {
                throw new IllegalStateException("disk full");
            }
            records.add(record);
        }
    }

    private static final class HostRegistry implements ServiceRegistry {
        private final HostActionHandler handler;

        HostRegistry(HostActionHandler handler) {
            this.handler = handler;
        }

        @Override public <T> Registration register(Class<T> type, T service) {
            throw new UnsupportedOperationException("read-only test registry");
        }

        @Override public <T> Optional<T> find(Class<T> type) {
            return type == HostActionHandler.class
                    ? Optional.of(type.cast(handler))
                    : Optional.empty();
        }

        @Override public <T> List<T> findAll(Class<T> type) {
            return find(type).map(List::of).orElseGet(List::of);
        }
    }

    private static final class NoopLogger implements PluginLogger {
        @Override public void info(String message, Object... args) {}
        @Override public void warn(String message, Object... args) {}
        @Override public void error(String message, Object... args) {}
        @Override public void error(String message, Throwable throwable) {}
        @Override public void debug(String message, Object... args) {}
    }
}

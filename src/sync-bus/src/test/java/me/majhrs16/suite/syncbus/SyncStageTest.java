package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.DeliveryEnvelope;
import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineServices;
import me.majhrs16.suite.api.pipeline.PipelineSettings;
import me.majhrs16.suite.api.pipeline.PipelineTrace;
import me.majhrs16.suite.api.pipeline.StageResult;
import me.majhrs16.suite.api.pipeline.StageTrace;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncStageTest {

    private final FakeBus bus = new FakeBus();

    @Test
    void inheritedPlanBroadcastsToEverySink() {
        StageResult result = stage().afterMessage(
                PipelineMessage.of(message()), context(OutputPlan.inherit()));

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertTrue(bus.deliverCalled);
        assertTrue(bus.targeted.isEmpty());
        assertTrue(result.outputs().isEmpty(),
                "a bus without surfaces has no target to report");
    }

    @Test
    void inheritedPlanReportsOneOutcomePerSurface() {
        bus.surface("discord");
        bus.surface("tcp");

        StageResult result = stage().afterMessage(
                PipelineMessage.of(message()), context(OutputPlan.inherit()));

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertTrue(bus.deliverCalled);
        assertEquals(List.of("sync.discord", "sync.tcp"),
                result.outputs().stream()
                        .map(me.majhrs16.suite.api.pipeline.OutputOutcome::target)
                        .toList());
    }

    @Test
    void declaredPlanSendsOnlyNamedSyncBackends() {
        bus.surface("discord");
        bus.surface("tcp");
        OutputPlan plan = OutputPlan.of(
                List.of("sync.discord", "sync.gtranslate", "sync.tcp"),
                me.majhrs16.suite.api.pipeline.ParallelMode.INHERIT);

        StageResult result = stage().afterMessage(
                PipelineMessage.of(message()), context(plan));

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(List.of("discord", "tcp"), bus.targeted,
                "only backends owned by this stage are sent");
        assertFalse(bus.deliverCalled);
    }

    @Test
    void declaredPlanWithoutOwnedSyncTargetsDoesNotFanOut() {
        bus.surface("discord");
        OutputPlan plan = OutputPlan.of(List.of("sync.gtranslate"),
                me.majhrs16.suite.api.pipeline.ParallelMode.INHERIT);

        StageResult result = stage().afterMessage(
                PipelineMessage.of(message()), context(plan));

        assertEquals(StageResult.Kind.NOT_APPLICABLE, result.kind());
        assertFalse(bus.deliverCalled);
        assertTrue(bus.targeted.isEmpty());
    }

    @Test
    void backendNamesMayContainDots() {
        assertEquals(List.of("discord.eu.west", "tcp"),
                SyncStage.syncBackends(List.of("sync.discord.eu.west", "sync.tcp")));
    }

    @Test
    void declaredPlanReportsOneOutcomePerTargetInOrder() {
        bus.surface("discord");
        bus.surface("tcp");
        bus.result("discord", new SyncResult.Ok(null));
        bus.result("tcp", new SyncResult.HostFailure("down"));
        OutputPlan plan = OutputPlan.of(List.of("sync.discord", "sync.tcp"),
                me.majhrs16.suite.api.pipeline.ParallelMode.PARALLEL);

        StageResult result = stage().afterMessage(
                PipelineMessage.of(message()), context(plan));

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(List.of("discord", "tcp"), bus.targeted);
        assertEquals(me.majhrs16.suite.api.pipeline.ParallelMode.PARALLEL, bus.mode);
        assertEquals(List.of("sync.discord", "sync.tcp"),
                result.outputs().stream()
                        .map(me.majhrs16.suite.api.pipeline.OutputOutcome::target)
                        .toList());
        assertTrue(result.outputs().get(0).isOk());
        assertFalse(result.outputs().get(1).isOk(),
                "a partial failure must stay visible in the aggregate");
    }

    @Test
    void allFailedTargetsAreNotApplicableButKeepExplicitOutcomes() {
        bus.surface("discord");
        bus.surface("tcp");
        bus.result("discord", new SyncResult.HostFailure("down"));
        bus.result("tcp", new SyncResult.Unsupported("not installed"));
        OutputPlan plan = OutputPlan.of(List.of("sync.discord", "sync.tcp"),
                me.majhrs16.suite.api.pipeline.ParallelMode.INHERIT);

        StageResult result = stage().afterMessage(
                PipelineMessage.of(message()), context(plan));

        assertEquals(StageResult.Kind.NOT_APPLICABLE, result.kind());
        assertEquals(2, result.outputs().size());
        assertTrue(result.outputs().stream().noneMatch(
                me.majhrs16.suite.api.pipeline.OutputOutcome::isOk));
    }

    @Test
    void sequentialModeIsForwardedToTheBus() {
        bus.surface("discord");
        OutputPlan plan = OutputPlan.of(List.of("sync.discord"),
                me.majhrs16.suite.api.pipeline.ParallelMode.SEQUENTIAL);

        stage().afterMessage(PipelineMessage.of(message()), context(plan));

        assertEquals(me.majhrs16.suite.api.pipeline.ParallelMode.SEQUENTIAL, bus.mode);
    }

    @Test
    void ownsOnlyRegisteredSinkBackends() {
        bus.surface("discord");
        SyncStage stage = stage();

        assertTrue(stage.ownsOutput("discord"));
        assertFalse(stage.ownsOutput("gtranslate"));
        assertFalse(stage.ownsOutput(null));
    }

    private SyncStage stage() {
        return new SyncStage(bus);
    }

    private static Message message() {
        return Message.builder().text("hello").build();
    }

    private static PipelineContext context(OutputPlan outputs) {
        return new Context(outputs);
    }

    private static final class FakeBus implements SyncBus {
        private boolean deliverCalled;
        private final List<String> targeted = new ArrayList<>();
        private final java.util.Map<String, SyncResult> results = new java.util.HashMap<>();
        private final List<SyncSurface> surfaces = new ArrayList<>();
        private me.majhrs16.suite.api.pipeline.ParallelMode mode;

        void result(String backend, SyncResult result) {
            results.put(backend, result);
        }

        void surface(String backend) {
            surfaces.add(SyncSurface.lcm(backend, SyncSurface.CURRENT_CONTRACT_VERSION));
        }

        @Override
        public List<SyncResult> send(Message message) {
            return List.of(new SyncResult.Ok(null));
        }

        @Override
        public List<SyncResult> sendTo(String backend, Message message) {
            return sendTo(List.of(backend), message,
                    me.majhrs16.suite.api.pipeline.ParallelMode.SEQUENTIAL);
        }

        @Override
        public List<SyncResult> sendTo(List<String> backends, Message message,
                                       me.majhrs16.suite.api.pipeline.ParallelMode mode) {
            return directed(backends, mode);
        }

        @Override
        public List<SyncResult> deliver(DeliveryEnvelope envelope) {
            deliverCalled = true;
            return surfaces.stream()
                    .<SyncResult>map(surface -> new SyncResult.Ok(null))
                    .toList();
        }

        @Override
        public List<SyncResult> deliverTo(List<String> backends, DeliveryEnvelope envelope,
                                          me.majhrs16.suite.api.pipeline.ParallelMode mode) {
            return directed(backends, mode);
        }

        private List<SyncResult> directed(List<String> backends,
                                          me.majhrs16.suite.api.pipeline.ParallelMode mode) {
            targeted.addAll(backends);
            this.mode = mode;
            return backends.stream()
                    .map(backend -> results.getOrDefault(backend, new SyncResult.Ok(null)))
                    .toList();
        }

        @Override public Set<SyncSurface> surfaces() {
            return new java.util.LinkedHashSet<>(surfaces);
        }
        @Override public SyncResult invoke(SyncCall call) {
            return new SyncResult.Unsupported("not used by this test");
        }
        @Override public List<SyncResult> broadcast(SyncCall call) { return List.of(); }
        @Override public void register(SyncSink sink) { }
        @Override public boolean unregister(String name) { return false; }
        @Override public Collection<SyncSink> sinks() { return List.of(); }
        @Override public Set<String> sinkNames() {
            return surfaces.stream()
                    .map(SyncSurface::backend)
                    .collect(java.util.stream.Collectors.toCollection(
                            java.util.LinkedHashSet::new));
        }
        @Override public void setInboundListener(SyncListener listener) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public boolean isEmpty() { return true; }
        @Override public SyncListener getInboundListener() { return null; }
        @Override public long getTotalBroadcast() { return 0; }
        @Override public long getTotalDropped() { return 0; }
        @Override public long getTotalRetries() { return 0; }
        @Override public long getTotalDeduped() { return 0; }
        @Override public int getGlobalQueueSize() { return 0; }
        @Override public int getGlobalQueueRemaining() { return 0; }
        @Override public void close() { }
    }

    private static final class Context implements PipelineContext {
        private final OutputPlan outputs;

        private Context(OutputPlan outputs) {
            this.outputs = outputs;
        }

        @Override public String messageId() { return "test"; }
        @Override public PipelineScope scope() { return PipelineScope.MESSAGE_POST; }
        @Override public Optional<ChannelRegistry> channels() { return Optional.empty(); }
        @Override public ActorDirectory actors() { return EmptyActors.INSTANCE; }
        @Override public PermissionChecker permissions() { return PermissionChecker.ALLOW_ALL; }
        @Override public PipelineSettings settings() {
            return new PipelineSettings(Language.EN, false, false);
        }
        @Override public PipelineServices services() { return EmptyServices.INSTANCE; }
        @Override public OutputPlan outputs() { return outputs; }
        @Override public PluginLogger logger() { return NoopLogger.INSTANCE; }
        @Override public PipelineTrace trace() { return EmptyTrace.INSTANCE; }
        @Override public long planVersion() { return 1L; }
    }

    private enum EmptyActors implements ActorDirectory {
        INSTANCE;

        @Override public List<Actor> onlinePlayers() { return List.of(); }
        @Override public Optional<Actor> byUuid(UUID uuid) { return Optional.empty(); }
        @Override public Optional<Actor> byName(String name) { return Optional.empty(); }
        @Override public Actor console() { return Actor.console("Server", Language.EN); }
    }

    private enum EmptyServices implements PipelineServices {
        INSTANCE;

        @Override public <T> Optional<T> find(Class<T> type) { return Optional.empty(); }
        @Override public <T> List<T> findAll(Class<T> type) { return List.of(); }
    }

    private enum EmptyTrace implements PipelineTrace {
        INSTANCE;

        @Override public void record(StageTrace entry) { }
        @Override public List<StageTrace> entries() { return List.of(); }
    }

    private enum NoopLogger implements PluginLogger {
        INSTANCE;

        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable throwable) { }
        @Override public void debug(String message, Object... args) { }
    }
}

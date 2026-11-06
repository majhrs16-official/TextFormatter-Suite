package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
        assertTrue(bus.broadcastCalled);
        assertTrue(bus.targeted.isEmpty());
    }

    @Test
    void declaredPlanSendsOnlyNamedSyncBackends() {
        OutputPlan plan = OutputPlan.of(List.of("sync.discord", "telegram.chat", "sync.tcp"),
                me.majhrs16.suite.api.pipeline.ParallelMode.INHERIT);

        StageResult result = stage().afterMessage(
                PipelineMessage.of(message()), context(plan));

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(List.of("discord", "tcp"), bus.targeted);
        assertFalse(bus.broadcastCalled);
    }

    @Test
    void declaredPlanWithoutSyncTargetsDoesNotFanOut() {
        OutputPlan plan = OutputPlan.of(List.of("telegram.chat"),
                me.majhrs16.suite.api.pipeline.ParallelMode.INHERIT);

        StageResult result = stage().afterMessage(
                PipelineMessage.of(message()), context(plan));

        assertEquals(StageResult.Kind.NOT_APPLICABLE, result.kind());
        assertFalse(bus.broadcastCalled);
        assertTrue(bus.targeted.isEmpty());
    }

    @Test
    void backendNamesMayContainDots() {
        assertEquals(List.of("discord.eu.west", "tcp"),
                SyncStage.syncBackends(List.of("sync.discord.eu.west", "sync.tcp")));
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
        private boolean broadcastCalled;
        private final List<String> targeted = new ArrayList<>();

        @Override
        public int broadcast(Message message) {
            broadcastCalled = true;
            return 1;
        }

        @Override
        public boolean sendTo(String sinkName, Message message) {
            targeted.add(sinkName);
            return true;
        }

        @Override public void register(SyncSink sink) { }
        @Override public boolean unregister(String name) { return false; }
        @Override public Collection<SyncSink> sinks() { return List.of(); }
        @Override public Set<String> sinkNames() { return Set.of(); }
        @Override public Map<String, CompletableFuture<Void>> broadcastAsync(Message message) {
            return Map.of();
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

package me.majhrs16.suite.iflow.hub;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.iflow.Router;
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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteStageTest {

    private static final Actor STEVE = new Actor(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Steve",
            Actor.ActorKind.PLAYER, Language.EN, null);

    @Test
    void emissionRateLimitDropsTheWholeEmission() {
        FakeRouter router = new FakeRouter();
        router.rateLimited = true;
        router.waitNanos = 250_000_000L;

        StageResult result = new RouteStage(router).beforeMessage(
                PipelineMessage.of(message()), context());

        assertEquals(StageResult.Kind.DROP, result.kind());
        assertEquals("rate limit exceeded: 250ms", result.detail());
    }

    @Test
    void recipientDecisionAndTransformedMessageContinue() {
        FakeRouter router = new FakeRouter();
        Message transformed = message().withText("rewritten");
        router.first = RouteOutcome.of(
                new RouteDecision(PolicyTarget.LOG, null, 0, STEVE, null), transformed);

        StageResult result = new RouteStage(router).perRecipient(
                PipelineMessage.of(message()).withRecipient(STEVE), context());

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(PolicyTarget.LOG, result.message().decision().target());
        assertEquals("rewritten", result.message().message().text());
    }

    @Test
    void channelRedirectReroutesOnceOnTheNewChannel() {
        FakeRouter router = new FakeRouter();
        router.first = RouteOutcome.of(
                new RouteDecision(PolicyTarget.CHANNEL_REDIRECT, "legacy", 0, STEVE, null, "staff"),
                message());
        router.second = RouteOutcome.of(
                new RouteDecision(PolicyTarget.LOG, null, 0, STEVE, null), message());

        StageResult result = new RouteStage(router).perRecipient(
                PipelineMessage.of(message()).withRecipient(STEVE), context());

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals(PolicyTarget.LOG, result.message().decision().target());
        assertEquals(List.of("chat", "staff"), router.routedChannels);
    }

    @Test
    void secondChannelMutationIsADecisionConflict() {
        FakeRouter router = new FakeRouter();
        router.first = RouteOutcome.of(
                new RouteDecision(PolicyTarget.CHANNEL_REDIRECT, "legacy", 0, STEVE, null, "staff"),
                message());
        router.second = RouteOutcome.of(
                new RouteDecision(PolicyTarget.CHANNEL_REDIRECT, "again", 0, STEVE, null, "other"),
                message());

        StageResult result = new RouteStage(router).perRecipient(
                PipelineMessage.of(message()).withRecipient(STEVE), context());

        assertEquals(StageResult.Kind.REJECT, result.kind());
        assertEquals("DECISION_CONFLICT", result.detail());
    }

    @Test
    void missingRecipientIsAnExplicitError() {
        StageResult result = new RouteStage(new FakeRouter()).perRecipient(
                PipelineMessage.of(message()), context());

        assertEquals(StageResult.Kind.ERROR, result.kind());
        assertEquals("route requires a recipient", result.detail());
    }

    private static Message message() {
        return Message.builder().text("hello").channel("chat").build();
    }

    private static PipelineContext context() {
        return new Context();
    }

    private static final class FakeRouter implements Router {
        private boolean rateLimited;
        private long waitNanos;
        private RouteOutcome first;
        private RouteOutcome second;
        private final List<String> routedChannels = new ArrayList<>();

        @Override
        public RouteOutcome route(Message message, Actor recipient) {
            routedChannels.add(message.channel());
            return routedChannels.size() == 1 ? first : second;
        }

        @Override public boolean hasPermission(Actor actor, String permission) { return true; }
        @Override public boolean checkEmissionRateLimit(Message message) { return !rateLimited; }
        @Override public long nanosUntilNextRateLimitWindow(Message message) { return waitNanos; }
    }

    private static final class Context implements PipelineContext {
        @Override public String messageId() { return "test"; }
        @Override public PipelineScope scope() { return PipelineScope.RECIPIENT; }
        @Override public Optional<ChannelRegistry> channels() { return Optional.empty(); }
        @Override public ActorDirectory actors() { return EmptyActors.INSTANCE; }
        @Override public PermissionChecker permissions() { return PermissionChecker.ALLOW_ALL; }
        @Override public PipelineSettings settings() {
            return new PipelineSettings(Language.EN, false, false);
        }
        @Override public PipelineServices services() { return EmptyServices.INSTANCE; }
        @Override public OutputPlan outputs() { return OutputPlan.inherit(); }
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

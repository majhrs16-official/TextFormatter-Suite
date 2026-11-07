package me.majhrs16.suite.host;

import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Channel;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelinePlan;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineServices;
import me.majhrs16.suite.api.pipeline.PipelineSettings;
import me.majhrs16.suite.api.pipeline.PipelineStage;
import me.majhrs16.suite.api.pipeline.PipelineStatus;
import me.majhrs16.suite.api.pipeline.StageResult;
import me.majhrs16.suite.api.pipeline.StageStatus;
import me.majhrs16.suite.api.pipeline.StageTrace;
import me.majhrs16.suite.api.render.ChatDelivery;
import me.majhrs16.suite.api.render.ComponentRenderedOutput;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineEngineTest {

    private static final Actor STEVE = new Actor(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Steve",
            Actor.ActorKind.PLAYER, Language.EN, null);
    private static final Actor ALEX = new Actor(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "Alex",
            Actor.ActorKind.PLAYER, Language.ES, null);

    private final RecordingDelivery delivery = new RecordingDelivery();
    private final ActorDirectory actors = new Directory();

    @Test
    void executesRecipientStagesInPlanOrderAndDeliversTheLastMessage() {
        PipelineStage first = recipientStage("first", unit ->
                StageResult.continueWith(unit.withMessage(
                        Message.builder().from(unit.message()).text("hello-A").build())));
        PipelineStage second = recipientStage("second", unit ->
                StageResult.continueWith(unit.withMessage(
                        Message.builder().from(unit.message()).text("hello-AB").build())));

        PipelineOutcome outcome = engine(
                PipelinePlan.of(List.of("first", "second"), 1L),
                List.of(new ResolvedStage("first", "m1", first),
                        new ResolvedStage("second", "m2", second))).dispatch(message());

        assertEquals(PipelineStatus.READY, outcome.status());
        assertEquals(1, outcome.delivered());
        assertEquals(List.of("hello-AB"), delivery.texts);
        assertEquals(1, delivery.recipients.size());
    }

    @Test
    void dropStageSilencesTheRecipientAndSkipsDelivery() {
        PipelineStage drop = recipientStage("drop", unit ->
                StageResult.drop(unit, "policy"));

        PipelineOutcome outcome = engine(
                PipelinePlan.of(List.of("drop"), 1L),
                List.of(new ResolvedStage("drop", "m", drop))).dispatch(message());

        assertEquals(1, outcome.silenced());
        assertEquals(0, outcome.delivered());
        assertTrue(delivery.texts.isEmpty());
        assertEquals(StageStatus.DROP, onlyTrace(outcome).status());
    }

    @Test
    void exceptionBecomesErrorAndCountsFailed() {
        PipelineStage broken = recipientStage("broken", unit -> {
            throw new IllegalStateException("boom");
        });

        PipelineOutcome outcome = engine(
                PipelinePlan.of(List.of("broken"), 1L),
                List.of(new ResolvedStage("broken", "m", broken))).dispatch(message());

        assertEquals(1, outcome.failed());
        assertEquals(0, outcome.delivered());
        assertTrue(delivery.texts.isEmpty());
        assertEquals(StageStatus.ERROR, onlyTrace(outcome).status());
    }

    @Test
    void emptyPlanReturnsNoPipelineAndDeliversNothing() {
        PipelineOutcome outcome = engine(PipelinePlan.empty(), List.of()).dispatch(message());

        assertEquals(PipelineStatus.NO_PIPELINE, outcome.status());
        assertEquals("no pipeline", outcome.skipReason());
        assertEquals(0, outcome.delivered());
        assertTrue(delivery.texts.isEmpty());
    }

    @Test
    void absentStageIsTracedAsSkipped() {
        PipelineStage stage = recipientStage("format", StageResult::continueWith);

        PipelineOutcome outcome = engine(
                PipelinePlan.of(List.of("normalize", "format"), 3L),
                List.of(new ResolvedStage("format", "textformatter", stage)),
                List.of(new SkippedStage("normalize", "module-inactive"))).dispatch(message());

        StageTrace skipped = outcome.trace().entries().stream()
                .filter(entry -> entry.status() == StageStatus.SKIPPED)
                .findFirst()
                .orElseThrow();
        assertEquals("normalize", skipped.stageId());
        assertEquals("module-inactive", skipped.detail());
        assertEquals(1, outcome.delivered());
    }

    @Test
    void preAndPostPhasesRunOncePerEmission() {
        AtomicInteger pre = new AtomicInteger();
        AtomicInteger post = new AtomicInteger();
        PipelineStage stage = new BaseStage("phases") {
            @Override
            public Set<PipelineScope> scopes() {
                return Set.of(PipelineScope.MESSAGE_PRE, PipelineScope.MESSAGE_POST);
            }

            @Override
            public StageResult beforeMessage(PipelineMessage message,
                                              me.majhrs16.suite.api.pipeline.PipelineContext context) {
                pre.incrementAndGet();
                return StageResult.continueWith(message);
            }

            @Override
            public StageResult afterMessage(PipelineMessage message,
                                             me.majhrs16.suite.api.pipeline.PipelineContext context) {
                post.incrementAndGet();
                return StageResult.continueWith(message);
            }
        };

        PipelineOutcome outcome = engine(
                PipelinePlan.of(List.of("phases"), 1L),
                List.of(new ResolvedStage("phases", "m", stage))).dispatch(messageToAll());

        assertEquals(1, pre.get());
        assertEquals(1, post.get());
        assertEquals(2, outcome.delivered());
    }

    @Test
    void cancelEventFromAStagePropagates() {
        PipelineStage cancel = recipientStage("cancel", unit ->
                StageResult.continueWith(unit.withCancelEvent(true)));

        PipelineOutcome outcome = engine(
                PipelinePlan.of(List.of("cancel"), 1L),
                List.of(new ResolvedStage("cancel", "m", cancel))).dispatch(message());

        assertTrue(outcome.cancelEvent());
    }

    @Test
    void renderedOutputReachesDeliveryWithoutFallback() {
        PipelineStage format = recipientStage("format", unit ->
                StageResult.continueWith(unit.withRendered(
                        new ComponentRenderedOutput(Component.text("rich"), "rich"))));

        engine(PipelinePlan.of(List.of("format"), 1L),
                List.of(new ResolvedStage("format", "m", format))).dispatch(message());

        assertEquals(List.of("rich"), delivery.texts);
    }

    @Test
    void rejectsStageOrderThatDivergesFromPlan() {
        PipelineStage first = recipientStage("first", StageResult::continueWith);
        PipelineStage second = recipientStage("second", StageResult::continueWith);

        assertThrows(IllegalArgumentException.class, () -> engine(
                PipelinePlan.of(List.of("first", "second"), 1L),
                List.of(new ResolvedStage("second", "m", second),
                        new ResolvedStage("first", "m", first))));
    }

    @Test
    void listedButUnresolvedPlanIsNoPipeline() {
        PipelineOutcome outcome = engine(
                PipelinePlan.of(List.of("normalize"), 4L),
                List.of(),
                List.of(new SkippedStage("normalize", "module-inactive"))).dispatch(message());

        assertEquals(PipelineStatus.NO_PIPELINE, outcome.status());
        assertEquals("no pipeline", outcome.skipReason());
        assertEquals(0, outcome.delivered());
        assertTrue(delivery.texts.isEmpty());
    }

    private PipelineEngine engine(PipelinePlan plan,
                                  List<ResolvedStage> stages) {
        return engine(plan, stages, List.of());
    }

    private PipelineEngine engine(PipelinePlan plan,
                                  List<ResolvedStage> stages,
                                  List<SkippedStage> skipped) {
        PipelineEnvironment environment = new PipelineEnvironment(
                actors,
                PermissionChecker.ALLOW_ALL,
                Optional.empty(),
                Optional.empty(),
                new PipelineSettings(Language.EN, false, false),
                new EmptyServices(),
                OutputPlan.inherit(),
                new NoopLogger(),
                false);
        return new PipelineEngine(
                plan,
                stages,
                skipped,
                new RecipientResolver(actors, PermissionChecker.ALLOW_ALL, new NoopLogger()),
                new DeliveryExecutor(delivery, null, false, new NoopLogger()),
                environment,
                plan.version());
    }

    private static Message message() {
        return Message.builder()
                .text("hello")
                .direction(Direction.specific(Channel.CHAT, STEVE))
                .build();
    }

    private static Message messageToAll() {
        return Message.builder()
                .text("hello")
                .direction(Direction.all())
                .build();
    }

    private static StageTrace onlyTrace(PipelineOutcome outcome) {
        return outcome.trace().entries().stream()
                .filter(entry -> entry.stageId().equals("drop")
                        || entry.stageId().equals("broken"))
                .findFirst()
                .orElseThrow();
    }

    private static PipelineStage recipientStage(
            String id,
            java.util.function.Function<PipelineMessage, StageResult> body) {
        return new BaseStage(id) {
            @Override
            public Set<PipelineScope> scopes() {
                return Set.of(PipelineScope.RECIPIENT);
            }

            @Override
            public StageResult perRecipient(PipelineMessage message,
                                            me.majhrs16.suite.api.pipeline.PipelineContext context) {
                return body.apply(message);
            }
        };
    }

    private abstract static class BaseStage implements me.majhrs16.suite.api.pipeline.PipelineStage {
        private final String id;

        private BaseStage(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }
    }

    private static final class Directory implements ActorDirectory {
        @Override public List<Actor> onlinePlayers() { return List.of(STEVE, ALEX); }
        @Override public Optional<Actor> byUuid(UUID uuid) { return Optional.empty(); }
        @Override public Optional<Actor> byName(String name) { return Optional.empty(); }
        @Override public Actor console() { return Actor.console("Server", Language.EN); }
    }

    private static final class EmptyServices implements PipelineServices {
        @Override public <T> Optional<T> find(Class<T> type) { return Optional.empty(); }
        @Override public <T> List<T> findAll(Class<T> type) { return List.of(); }
    }

    private static final class RecordingDelivery implements ChatDelivery {
        private final List<Actor> recipients = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();

        @Override
        public void deliver(Actor recipient, Component rendered, Message original) {
            recipients.add(recipient);
            texts.add(PlainTextComponentSerializer.plainText().serialize(rendered));
        }

        @Override
        public void deliverConsole(Component rendered) {
            texts.add("console:" + PlainTextComponentSerializer.plainText().serialize(rendered));
        }

        @Override public void playSound(Actor recipient, me.majhrs16.suite.api.message.SoundSpec sound) { }
        @Override public boolean hasSound(String soundName) { return false; }
    }

    private static final class NoopLogger implements PluginLogger {
        @Override public void info(String message, Object... args) {}
        @Override public void warn(String message, Object... args) {}
        @Override public void error(String message, Object... args) {}
        @Override public void error(String message, Throwable throwable) {}
        @Override public void debug(String message, Object... args) {}
    }
}

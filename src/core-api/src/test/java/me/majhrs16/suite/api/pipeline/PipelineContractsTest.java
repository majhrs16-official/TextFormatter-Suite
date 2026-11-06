package me.majhrs16.suite.api.pipeline;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineContractsTest {

    private static Message message() {
        return Message.builder().text("hello").build();
    }

    @Test
    void inheritedOutputPlanHasNoTargets() {
        OutputPlan plan = OutputPlan.inherit();

        assertFalse(plan.declared());
        assertTrue(plan.targets().isEmpty());
        assertEquals(ParallelMode.INHERIT, plan.parallel());
    }

    @Test
    void declaredEmptyPlanIsNotInheritance() {
        OutputPlan plan = OutputPlan.none();

        assertTrue(plan.declared());
        assertTrue(plan.targets().isEmpty());
    }

    @Test
    void outputTargetsKeepOrderAndSupportDots() {
        OutputPlan plan = OutputPlan.of(
                List.of("sync.discord", "sync.bukkit.local"),
                ParallelMode.SEQUENTIAL);

        assertEquals(List.of("sync.discord", "sync.bukkit.local"), plan.targets());
        assertEquals(ParallelMode.SEQUENTIAL, plan.parallel());
    }

    @Test
    void outputTargetsRequireMultiplexerAndBackend() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputPlan.of(List.of("discord"), ParallelMode.INHERIT));
        assertThrows(IllegalArgumentException.class,
                () -> OutputPlan.of(List.of(".discord"), ParallelMode.INHERIT));
        assertThrows(IllegalArgumentException.class,
                () -> OutputPlan.of(List.of("sync."), ParallelMode.INHERIT));
        assertThrows(IllegalArgumentException.class,
                () -> OutputPlan.of(List.of("sync..discord"), ParallelMode.INHERIT));
    }

    @Test
    void inheritedPlanCannotCarryTargets() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutputPlan(false, List.of("sync.discord"), ParallelMode.INHERIT));
    }

    @Test
    void stageResultMapsKindToTraceStatus() {
        PipelineMessage unit = PipelineMessage.of(message());

        assertEquals(StageStatus.RUN,
                StageResult.continueWith(unit).traceStatus());
        assertEquals(StageStatus.NOT_APPLICABLE,
                StageResult.notApplicable(unit).traceStatus());
        assertEquals(StageStatus.DROP,
                StageResult.drop(unit, "policy").traceStatus());
        assertEquals(StageStatus.REJECT,
                StageResult.reject(unit, "policy").traceStatus());
        assertEquals(StageStatus.ERROR,
                StageResult.error(unit, "boom").traceStatus());
    }

    @Test
    void onlyContinueAndNotApplicableKeepProcessing() {
        PipelineMessage unit = PipelineMessage.of(message());

        assertFalse(StageResult.continueWith(unit).terminal());
        assertFalse(StageResult.notApplicable(unit).terminal());
        assertTrue(StageResult.drop(unit, "policy").terminal());
        assertTrue(StageResult.reject(unit, "policy").terminal());
        assertTrue(StageResult.error(unit, "boom").terminal());
    }

    @Test
    void pipelineMessageReplacesOneSlotAtATime() {
        Message original = message();
        Actor recipient = new Actor(null, "alex", Actor.ActorKind.PLAYER, null, null);
        PipelineMessage unit = PipelineMessage.of(original).withRecipient(recipient);

        assertSame(original, unit.message());
        assertSame(recipient, unit.recipient());
        assertNull(unit.decision());
        assertNull(unit.rendered());
        assertFalse(unit.cancelEvent());

        Message next = Message.builder().text("translated").build();
        PipelineMessage translated = unit.withMessage(next);
        assertSame(next, translated.message());
        assertSame(recipient, translated.recipient());
        assertTrue(translated.withCancelEvent(true).cancelEvent());
    }

    @Test
    void planKeepsTheDeclaredOrder() {
        PipelinePlan plan = PipelinePlan.of(
                List.of("route", "translate", "format", "sync"), 7L);

        assertEquals(List.of("route", "translate", "format", "sync"), plan.stageIds());
        assertEquals(7L, plan.version());
        assertEquals(PipelineStatus.READY, plan.status());
        assertFalse(plan.isEmpty());
    }

    @Test
    void emptyPlanIsValidButHasNoPipeline() {
        PipelinePlan plan = PipelinePlan.empty();

        assertTrue(plan.isEmpty());
        assertEquals(PipelineStatus.NO_PIPELINE, plan.status());
    }

    @Test
    void planRejectsInvalidIdsDuplicatesAndNonTerminalSync() {
        assertThrows(IllegalArgumentException.class,
                () -> PipelinePlan.of(List.of("Route"), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> PipelinePlan.of(List.of("2fast"), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> PipelinePlan.of(List.of("format", "format"), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> PipelinePlan.of(List.of("sync", "format"), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> PipelinePlan.of(List.of("format"), -1L));
    }

    @Test
    void syncIsValidAsTheLastStage() {
        PipelinePlan plan = PipelinePlan.of(List.of("format", "sync"), 1L);

        assertEquals("sync", plan.stageIds().get(plan.stageIds().size() - 1));
    }
}

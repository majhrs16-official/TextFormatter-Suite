package me.majhrs16.suite.api.message;

import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.ParallelMode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageOutputPlanTest {

    @Test
    void defaultPlanIsInherited() {
        Message message = Message.builder().text("hello").build();

        assertEquals(OutputPlan.inherit(), message.outputPlan());
    }

    @Test
    void builderKeepsADeclaredPlan() {
        OutputPlan plan = OutputPlan.of(
                List.of("sync.discord"), ParallelMode.SEQUENTIAL);

        Message message = Message.builder()
                .text("hello")
                .outputPlan(plan)
                .build();

        assertEquals(plan, message.outputPlan());
    }

    @Test
    void withOutputPlanDoesNotMutateTheOriginal() {
        Message original = Message.builder().text("hello").build();
        OutputPlan plan = OutputPlan.of(
                List.of("sync.discord", "sync.tcp"), ParallelMode.PARALLEL);

        Message changed = original.withOutputPlan(plan);

        assertEquals(OutputPlan.inherit(), original.outputPlan());
        assertEquals(plan, changed.outputPlan());
        assertEquals(original.id(), changed.id());
    }

    @Test
    void builderFromPreservesThePlan() {
        OutputPlan plan = OutputPlan.of(
                List.of("iflow.iflow-wasm"), ParallelMode.INHERIT);
        Message original = Message.builder()
                .text("hello")
                .outputPlan(plan)
                .build();

        Message copy = Message.builder().from(original).build();

        assertEquals(plan, copy.outputPlan());
    }

    @Test
    void declaredEmptyPlanMeansNoOutputs() {
        Message message = Message.builder()
                .text("hello")
                .outputPlan(OutputPlan.none())
                .build();

        assertTrue(message.outputPlan().declared());
        assertTrue(message.outputPlan().targets().isEmpty());
        assertEquals(ParallelMode.INHERIT, message.outputPlan().parallel());
    }

    @Test
    void jsonExposesTheDeclaredOutputs() {
        Message message = Message.builder()
                .text("hello")
                .outputPlan(OutputPlan.of(
                        List.of("sync.discord"), ParallelMode.SEQUENTIAL))
                .build();

        String json = message.toJson();

        assertTrue(json.contains("\"outputsDeclared\":true"));
        assertTrue(json.contains("\"outputs\":[\"sync.discord\"]"));
        assertTrue(json.contains("\"outputsParallel\":\"SEQUENTIAL\""));
    }
}

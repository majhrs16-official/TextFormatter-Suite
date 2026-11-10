package me.majhrs16.suite.host;

import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.ParallelMode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputPlanResolverTest {

    @Test
    void messageTargetsWinOverSuiteTargets() {
        OutputPlan message = OutputPlan.of(
                List.of("sync.discord"), ParallelMode.INHERIT);
        OutputPlan suite = OutputPlan.of(
                List.of("sync.telegram"), ParallelMode.PARALLEL);

        OutputPlan effective = OutputPlanResolver.resolve(message, suite);

        assertTrue(effective.declared());
        assertEquals(List.of("sync.discord"), effective.targets());
        assertEquals(ParallelMode.PARALLEL, effective.parallel());
    }

    @Test
    void suiteTargetsApplyWhenMessageInherits() {
        OutputPlan suite = OutputPlan.of(
                List.of("sync.discord", "sync.tcp"), ParallelMode.SEQUENTIAL);

        OutputPlan effective = OutputPlanResolver.resolve(OutputPlan.inherit(), suite);

        assertTrue(effective.declared());
        assertEquals(List.of("sync.discord", "sync.tcp"), effective.targets());
        assertEquals(ParallelMode.SEQUENTIAL, effective.parallel());
    }

    @Test
    void noDeclarationKeepsTheModuleDefault() {
        OutputPlan effective = OutputPlanResolver.resolve(
                OutputPlan.inherit(), OutputPlan.inherit());

        assertFalse(effective.declared());
        assertTrue(effective.targets().isEmpty());
        assertEquals(ParallelMode.INHERIT, effective.parallel());
    }

    @Test
    void messageParallelOverridesSuiteParallel() {
        OutputPlan message = OutputPlan.of(
                List.of("sync.discord"), ParallelMode.SEQUENTIAL);
        OutputPlan suite = OutputPlan.of(
                List.of("sync.telegram"), ParallelMode.PARALLEL);

        OutputPlan effective = OutputPlanResolver.resolve(message, suite);

        assertEquals(ParallelMode.SEQUENTIAL, effective.parallel());
    }

    @Test
    void messageInheritsSuiteParallelWhileDeclaringTargets() {
        OutputPlan message = OutputPlan.of(
                List.of("sync.discord"), ParallelMode.INHERIT);
        OutputPlan suite = OutputPlan.of(
                List.of("sync.telegram"), ParallelMode.SEQUENTIAL);

        OutputPlan effective = OutputPlanResolver.resolve(message, suite);

        assertEquals(ParallelMode.SEQUENTIAL, effective.parallel());
    }

    @Test
    void declaredEmptyMessageMeansNoOutputsEvenIfSuiteDeclaresTargets() {
        OutputPlan suite = OutputPlan.of(
                List.of("sync.discord"), ParallelMode.PARALLEL);

        OutputPlan effective = OutputPlanResolver.resolve(OutputPlan.none(), suite);

        assertTrue(effective.declared());
        assertTrue(effective.targets().isEmpty());
    }

    @Test
    void originNamesTheLevelThatDeclaredTheTargets() {
        OutputPlan message = OutputPlan.of(
                List.of("sync.discord"), ParallelMode.INHERIT);
        OutputPlan suite = OutputPlan.of(
                List.of("sync.telegram"), ParallelMode.PARALLEL);

        assertEquals(OutputPlanResolver.Origin.MESSAGE,
                OutputPlanResolver.resolveDetailed(message, suite).origin());
        assertEquals(OutputPlanResolver.Origin.SUITE,
                OutputPlanResolver.resolveDetailed(OutputPlan.inherit(), suite).origin());
        assertEquals(OutputPlanResolver.Origin.MODULE_DEFAULT,
                OutputPlanResolver.resolveDetailed(
                        OutputPlan.inherit(), OutputPlan.inherit()).origin());
    }

    @Test
    void suiteParallelAppliesEvenWithoutSuiteTargets() {
        OutputPlan suite = new OutputPlan(
                false, List.of(), ParallelMode.SEQUENTIAL);

        OutputPlan effective = OutputPlanResolver.resolve(OutputPlan.inherit(), suite);

        assertFalse(effective.declared());
        assertEquals(ParallelMode.SEQUENTIAL, effective.parallel());
    }
}

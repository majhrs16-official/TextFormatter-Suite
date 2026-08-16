package me.majhrs16.suite.kernel;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleGraphTest {

    private static final Environment ENV = new Environment(SemVer.of(3, 1, 0), SemVer.of(21, 0, 0));

    @Test
    void resolvesModulesWithoutDependencies() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module a = Modules.simple("a", 3);
        Module b = Modules.simple("b", 3);

        ResolutionResult result = graph.resolve(List.of(a, b));

        assertTrue(result.allResolved(), "expected all resolved, got " + result);
        assertEquals(2, result.resolved().size());
    }

    @Test
    void resolvesDependencyChain() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module provider = Modules.providing("storage", 3, Capability.of("storage", SemVer.of(1, 0, 0)));
        Module consumer = Modules.requiring("consumer", 3,
                Requirement.atLeast("storage", SemVer.of(1, 0, 0)));

        ResolutionResult result = graph.resolve(List.of(consumer, provider));

        assertTrue(result.allResolved(), "expected resolved, got " + result.reasonOf(consumer));
        assertEquals(2, result.resolved().size());
    }

    @Test
    void rejectsModuleCompiledAgainstNewerContract() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module future = Modules.named("future", SemVer.of(1, 0, 0), 4, d -> d);

        ResolutionResult result = graph.resolve(List.of(future));

        assertEquals(ResolutionStatus.CONTRACT_MISMATCH, result.of(future));
        assertTrue(result.reasonOf(future).contains("4.0"));
    }

    @Test
    void acceptsOlderContractThanHostWithinSameMajor() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module older = Modules.simple("older", 3);

        ResolutionResult result = graph.resolve(List.of(older));

        assertEquals(ResolutionStatus.RESOLVED, result.of(older));
    }

    @Test
    void rejectsContractWithOlderMajor() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module legacy = Modules.simple("legacy", 2);

        ResolutionResult result = graph.resolve(List.of(legacy));

        assertEquals(ResolutionStatus.CONTRACT_MISMATCH, result.of(legacy));
    }

    @Test
    void rejectsJvmTooNew() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module needsOlderJvm = Modules.named("oldjvm", SemVer.of(1, 0, 0), 3,
                d -> d.jvmRange(0, 11));

        ResolutionResult result = graph.resolve(List.of(needsOlderJvm));

        assertEquals(ResolutionStatus.JVM_MISMATCH, result.of(needsOlderJvm));
    }

    @Test
    void rejectsJvmTooOld() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module needsNewerJvm = Modules.named("newjvm", SemVer.of(1, 0, 0), 3,
                d -> d.jvmRange(25, 0));

        ResolutionResult result = graph.resolve(List.of(needsNewerJvm));

        assertEquals(ResolutionStatus.JVM_MISMATCH, result.of(needsNewerJvm));
    }

    @Test
    void acceptsJvmWithinRange() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module ok = Modules.named("okjvm", SemVer.of(1, 0, 0), 3,
                d -> d.jvmRange(17, 21));

        ResolutionResult result = graph.resolve(List.of(ok));

        assertEquals(ResolutionStatus.RESOLVED, result.of(ok));
    }

    @Test
    void rejectsUnsatisfiedRequirement() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module consumer = Modules.requiring("consumer", 3,
                Requirement.atLeast("missing", SemVer.of(1, 0, 0)));

        ResolutionResult result = graph.resolve(List.of(consumer));

        assertEquals(ResolutionStatus.UNSATISFIED_REQUIREMENT, result.of(consumer));
        assertTrue(result.reasonOf(consumer).contains("missing"));
    }

    @Test
    void rejectsSatisfiedByNothingAfterFixpoint() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module consumer = Modules.requiring("runout", 3,
                Requirement.atLeast("core", SemVer.of(5, 0, 0)));
        Module wrongVersion = Modules.providing("core", 3,
                Capability.of("core", SemVer.of(1, 0, 0)));

        ResolutionResult result = graph.resolve(List.of(consumer, wrongVersion));

        assertEquals(ResolutionStatus.RESOLVED, result.of(wrongVersion));
        assertEquals(ResolutionStatus.UNSATISFIED_REQUIREMENT, result.of(consumer));
    }

    @Test
    void detectsCircularDependency() {
        ModuleGraph graph = new ModuleGraph(ENV);
        Module a = Modules.bothWays("a", "dep.b", "dep.a");
        Module b = Modules.bothWays("b", "dep.a", "dep.b");

        ResolutionResult result = graph.resolve(List.of(a, b));

        assertTrue(result.resolved().isEmpty(), "cycle must not resolve");
        assertEquals(ResolutionStatus.CYCLE, result.of(a));
        assertEquals(ResolutionStatus.CYCLE, result.of(b));
    }
}
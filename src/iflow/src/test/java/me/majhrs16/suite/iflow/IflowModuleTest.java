package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.kernel.ResolutionStatus;
import me.majhrs16.suite.textformatter.TextFormatterModule;

import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the cross-module dependency: iFlow requires {@code channel-api},
 * which the TextFormatter module provides. The kernel graph must resolve both
 * when they are present together and must flag iFlow as unmet on its own.
 */
class IflowModuleTest {

    @Test
    void registersModuleForServiceLoader() {
        Module module = ServiceLoader.load(Module.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.descriptor().name().equals("iflow"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("iflow module not registered"));

        assertEquals("iflow", module.descriptor().name());
    }

    @Test
    void declaresRouterCapabilityAndChannelApiRequirement() {
        ModuleDescriptor descriptor = new IflowModule().descriptor();

        assertEquals("2.1.0", descriptor.contractVersion().toString());
        assertTrue(descriptor.provides().contains(
            Capability.of("router", SemVer.of(2, 1, 0))));
        assertTrue(descriptor.requires().contains(
            me.majhrs16.suite.api.Requirement.atLeast("channel-api", SemVer.of(2, 1, 0))));
    }

    @Test
    void resolvesAlongsideTextFormatterInRealGraph() {
        ModuleGraph graph = new ModuleGraph(Environment.current());
        ResolutionResult result = graph.resolve(java.util.List.of(
            new IflowModule(),
            new TextFormatterModule()));

        assertTrue(result.allResolved(), "both modules must resolve together");
    }

    @Test
    void aloneIsUnsatisfiedWithoutChannelApi() {
        me.majhrs16.suite.api.Module iflow = new IflowModule();
        ModuleGraph graph = new ModuleGraph(Environment.current());
        ResolutionResult result = graph.resolve(java.util.List.of(iflow));

        assertEquals(ResolutionStatus.UNSATISFIED_REQUIREMENT, result.of(iflow));
    }
}
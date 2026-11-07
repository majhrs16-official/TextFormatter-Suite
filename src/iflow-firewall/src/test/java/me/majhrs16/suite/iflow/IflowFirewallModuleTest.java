package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.kernel.ResolutionStatus;

import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The iFlow firewall consumes {@code ChannelRegistry} optionally from the
 * service registry; it no longer declares {@code channel-api} as a
 * requirement. This keeps the engine usable without {@code textformatter}.
 */
class IflowFirewallModuleTest {

    @Test
    void registersModuleForServiceLoader() {
        Module module = ServiceLoader.load(Module.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.descriptor().name().equals("iflow-firewall"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("iflow-firewall module not registered"));

        assertEquals("iflow-firewall", module.descriptor().name());
    }

    @Test
    void declaresRouterCapabilityWithoutRequiringChannelApi() {
        ModuleDescriptor descriptor = new IflowFirewallModule().descriptor();

        assertEquals("1.0.0", descriptor.contractVersion().toString());
        assertTrue(descriptor.provides().contains(
            Capability.of("router", SemVer.of(1, 0, 0))));
        assertTrue(descriptor.requires().isEmpty());
    }

    @Test
    void resolvesWithoutTextFormatter() {
        Module iflow = new IflowFirewallModule();
        ResolutionResult result = new ModuleGraph(Environment.current())
            .resolve(java.util.List.of(iflow));

        assertEquals(ResolutionStatus.RESOLVED, result.of(iflow));
    }
}

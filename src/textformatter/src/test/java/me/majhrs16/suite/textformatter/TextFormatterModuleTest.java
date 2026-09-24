package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.kernel.ResolutionStatus;

import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the module declares itself in {@code META-INF/services} and that a
 * platform host can discover it with {@link ServiceLoader} exactly as the
 * kernel does when building the dependency graph, plus an end-to-end check
 * against the real {@link me.majhrs16.suite.kernel.ModuleGraph}.
 */
class TextFormatterModuleTest {

    @Test
    void registersModuleForServiceLoader() {
        Module module = ServiceLoader.load(Module.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.descriptor().name().equals("textformatter"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("textformatter module not registered"));

        assertNotNull(module);
    }

    @Test
    void declaresChannelApiCapability() {
        ModuleDescriptor descriptor = new TextFormatterModule().descriptor();

        assertEquals("textformatter", descriptor.name());
        assertEquals("2.1.0", descriptor.version().toString());
        assertEquals("2.1.0", descriptor.contractVersion().toString());
        assertTrue(descriptor.jvmMin() <= Runtime.version().feature());

        Capability channelApi = Capability.of("channel-api", me.majhrs16.suite.api.SemVer.of(2, 1, 0));
        assertTrue(descriptor.provides().contains(channelApi));
    }

    @Test
    void requiresNothingToActivate() {
        ModuleDescriptor descriptor = new TextFormatterModule().descriptor();
        assertTrue(descriptor.requires().isEmpty());
    }

    @Test
    void resolvesInRealKernelGraph() {
        me.majhrs16.suite.api.Module module = new TextFormatterModule();
        ModuleGraph graph = new ModuleGraph(Environment.current());
        ResolutionResult result = graph.resolve(java.util.List.of(module));

        assertEquals(ResolutionStatus.RESOLVED, result.of(module),
            "textformatter must resolve against the %s host".formatted(
                Environment.current().contractVersion()));
    }
}
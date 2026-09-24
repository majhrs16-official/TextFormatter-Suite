package me.majhrs16.suite.coretranslator;

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

class CoreTranslatorModuleTest {

    @Test
    void registersModuleForServiceLoader() {
        Module module = ServiceLoader.load(Module.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.descriptor().name().equals("coretranslator"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("coretranslator module not registered"));

        assertEquals("coretranslator", module.descriptor().name());
    }

    @Test
    void declaresDeprecatedLegacyCapability() {
        ModuleDescriptor descriptor = new CoreTranslatorModule().descriptor();
        assertTrue(descriptor.provides().contains(
            Capability.of("coretranslator-legacy", SemVer.of(1, 0, 0))));
        assertEquals("1.0.0", descriptor.version().toString());
    }

    @Test
    void resolvesInRealKernelGraph() {
        me.majhrs16.suite.api.Module module = new CoreTranslatorModule();
        ResolutionResult result = new ModuleGraph(Environment.current())
            .resolve(java.util.List.of(module));

        assertEquals(ResolutionStatus.RESOLVED, result.of(module));
    }
}
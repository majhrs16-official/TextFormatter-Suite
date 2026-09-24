package me.majhrs16.suite.gtranslate;

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

class GTranslateModuleTest {

    @Test
    void registersModuleForServiceLoader() {
        Module module = ServiceLoader.load(Module.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.descriptor().name().equals("gtranslate"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("gtranslate module not registered"));

        assertEquals("gtranslate", module.descriptor().name());
    }

    @Test
    void providesTranslatorCapability() {
        ModuleDescriptor descriptor = new GTranslateModule().descriptor();
        assertTrue(descriptor.provides().contains(
            Capability.of("translator", SemVer.of(2, 1, 0))));
    }

    @Test
    void resolvesInRealKernelGraph() {
        me.majhrs16.suite.api.Module module = new GTranslateModule();
        ResolutionResult result = new ModuleGraph(Environment.current())
            .resolve(java.util.List.of(module));

        assertEquals(ResolutionStatus.RESOLVED, result.of(module));
    }
}
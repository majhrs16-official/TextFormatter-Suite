package me.majhrs16.suite.ltranslate;

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

class LTranslateModuleTest {

    @Test
    void registersModuleForServiceLoader() {
        Module module = ServiceLoader.load(Module.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.descriptor().name().equals("ltranslate"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("ltranslate module not registered"));

        assertEquals("ltranslate", module.descriptor().name());
    }

    @Test
    void providesTranslatorCapability() {
        ModuleDescriptor descriptor = new LTranslateModule().descriptor();
        assertTrue(descriptor.provides().contains(
            Capability.of("translator", SemVer.of(2, 1, 0))));
    }

    @Test
    void resolvesInRealKernelGraph() {
        me.majhrs16.suite.api.Module module = new LTranslateModule();
        ResolutionResult result = new ModuleGraph(Environment.current())
            .resolve(java.util.List.of(module));

        assertEquals(ResolutionStatus.RESOLVED, result.of(module));
    }
}
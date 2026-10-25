package me.majhrs16.suite.gtranslate;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.chattranslator.ChatTranslatorModule;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.kernel.ResolutionStatus;

import org.junit.jupiter.api.Test;

import java.util.List;
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
    void requiresChatTranslatorCapability() {
        ModuleDescriptor descriptor = new GTranslateModule().descriptor();
        assertTrue(descriptor.requires().stream()
            .anyMatch(r -> r.name().equals("chattranslator")));
    }

    @Test
    void resolvesInRealKernelGraph() {
        me.majhrs16.suite.api.Module gtranslateModule = new GTranslateModule();
        me.majhrs16.suite.api.Module chatTranslatorModule = new ChatTranslatorModule();
        ResolutionResult result = new ModuleGraph(Environment.current())
            .resolve(List.of(gtranslateModule, chatTranslatorModule));

        assertEquals(ResolutionStatus.RESOLVED, result.of(gtranslateModule));
        assertEquals(ResolutionStatus.RESOLVED, result.of(chatTranslatorModule));
    }
}
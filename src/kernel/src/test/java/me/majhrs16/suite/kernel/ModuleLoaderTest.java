package me.majhrs16.suite.kernel;

import me.majhrs16.suite.api.Module;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleLoaderTest {

    @Test
    void discoversProviderFromMetaInfServices() throws Exception {
        List<Module> modules = ModuleLoader.discover(ModuleLoaderTest.class.getClassLoader());

        // Should at least discover the SPI fixture module
        assertTrue(modules.stream().anyMatch(m -> m.descriptor().name().equals("spi-fixture")),
            "expected spi-fixture module to be discovered");
    }

    @Test
    void resolvesDiscoveredModuleAgainstHost() {
        List<Module> modules = ModuleLoader.discover();

        // Filter to only the spi-fixture module for this test
        List<Module> fixtureOnly = modules.stream()
            .filter(m -> m.descriptor().name().equals("spi-fixture"))
            .toList();

        Environment env = new Environment(me.majhrs16.suite.api.SemVer.of(3, 0, 0),
                me.majhrs16.suite.api.SemVer.of(21, 0, 0));
        ModuleGraph graph = new ModuleGraph(env);
        ResolutionResult result = graph.resolve(fixtureOnly);

        assertTrue(result.allResolved());
        assertEquals("spi-fixture", result.resolved().get(0).descriptor().name());
    }
}
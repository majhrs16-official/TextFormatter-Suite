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

        assertEquals(1, modules.size(), "expected the SPI fixture only");
        assertEquals("spi-fixture", modules.get(0).descriptor().name());
    }

    @Test
    void resolvesDiscoveredModuleAgainstHost() {
        List<Module> modules = ModuleLoader.discover();

        Environment env = new Environment(me.majhrs16.suite.api.SemVer.of(3, 0, 0),
                me.majhrs16.suite.api.SemVer.of(21, 0, 0));
        ModuleGraph graph = new ModuleGraph(env);
        ResolutionResult result = graph.resolve(modules);

        assertTrue(result.allResolved());
        assertEquals("spi-fixture", result.resolved().get(0).descriptor().name());
    }
}
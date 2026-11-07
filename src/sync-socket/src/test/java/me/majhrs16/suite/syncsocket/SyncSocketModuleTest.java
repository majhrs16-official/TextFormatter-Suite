package me.majhrs16.suite.syncsocket;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.kernel.ResolutionStatus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncSocketModuleTest {

    @Test
    void registersModuleForServiceLoader() {
        Module module = ServiceLoader.load(Module.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.descriptor().name().equals("sync-socket"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("sync-socket module not registered"));

        assertEquals("sync-socket", module.descriptor().name());
    }

    @Test
    void providesSyncSinkWithoutRequiringTheBus() {
        ModuleDescriptor descriptor = new SyncSocketModule().descriptor();
        assertTrue(descriptor.provides().contains(
            Capability.of("sync-sink", SemVer.of(1, 0, 0))));
        assertTrue(descriptor.requires().isEmpty());
    }

    @Test
    void resolvesWithoutTheSyncBus() {
        me.majhrs16.suite.api.Module syncSocketModule = new SyncSocketModule();
        ResolutionResult result = new ModuleGraph(Environment.current())
            .resolve(List.of(syncSocketModule));

        assertEquals(ResolutionStatus.RESOLVED, result.of(syncSocketModule));
    }
}

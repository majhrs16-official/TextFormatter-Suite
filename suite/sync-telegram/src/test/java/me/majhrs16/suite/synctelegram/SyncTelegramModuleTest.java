package me.majhrs16.suite.synctelegram;

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

class SyncTelegramModuleTest {

    @Test
    void registersModuleForServiceLoader() {
        Module module = ServiceLoader.load(Module.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.descriptor().name().equals("sync-telegram"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("sync-telegram module not registered"));

        assertEquals("sync-telegram", module.descriptor().name());
    }

    @Test
    void providesSyncSinkCapability() {
        ModuleDescriptor descriptor = new SyncTelegramModule().descriptor();
        assertTrue(descriptor.provides().contains(
            Capability.of("sync-sink", SemVer.of(2, 1, 0))));
    }

    @Test
    void resolvesInRealKernelGraph() {
        Module module = new SyncTelegramModule();
        ResolutionResult result = new ModuleGraph(Environment.current())
            .resolve(List.of(module));

        assertEquals(ResolutionStatus.RESOLVED, result.of(module));
    }
}
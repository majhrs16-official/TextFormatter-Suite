package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.kernel.ResolutionStatus;
import me.majhrs16.suite.syncbus.SyncBusModule;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncDiscordModuleTest {

    @Test
    void registersModuleForServiceLoader() {
        Module module = ServiceLoader.load(Module.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.descriptor().name().equals("sync-discord"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("sync-discord module not registered"));

        assertEquals("sync-discord", module.descriptor().name());
    }

    @Test
    void providesSyncSinkCapability() {
        ModuleDescriptor descriptor = new SyncDiscordModule().descriptor();
        assertTrue(descriptor.provides().contains(
            Capability.of("sync-sink", SemVer.of(2, 1, 0))));
        assertTrue(descriptor.requires().stream()
            .anyMatch(r -> r.name().equals("sync-bus")));
    }

    @Test
    void requiresSyncBusCapability() {
        ModuleDescriptor descriptor = new SyncDiscordModule().descriptor();
        assertTrue(descriptor.requires().stream()
            .anyMatch(r -> r.name().equals("sync-bus")));
    }

    @Test
    void resolvesInRealKernelGraph() {
        Module syncDiscordModule = new SyncDiscordModule();
        Module syncBusModule = new SyncBusModule();
        ResolutionResult result = new ModuleGraph(Environment.current())
            .resolve(List.of(syncDiscordModule, syncBusModule));

        assertEquals(ResolutionStatus.RESOLVED, result.of(syncDiscordModule));
        assertEquals(ResolutionStatus.RESOLVED, result.of(syncBusModule));
    }
}
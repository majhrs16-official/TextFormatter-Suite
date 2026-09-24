package me.majhrs16.suite.synctcpudp;

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

class SyncTcpUdpModuleTest {

    @Test
    void registersModuleForServiceLoader() {
        Module module = ServiceLoader.load(Module.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.descriptor().name().equals("sync-tcpudp"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("sync-tcpudp module not registered"));

        assertEquals("sync-tcpudp", module.descriptor().name());
    }

    @Test
    void providesSyncSinkCapability() {
        ModuleDescriptor descriptor = new SyncTcpUdpModule().descriptor();
        assertTrue(descriptor.provides().contains(
            Capability.of("sync-sink", SemVer.of(2, 1, 0))));
    }

    @Test
    void resolvesInRealKernelGraph() {
        me.majhrs16.suite.api.Module module = new SyncTcpUdpModule();
        ResolutionResult result = new ModuleGraph(Environment.current())
            .resolve(java.util.List.of(module));

        assertEquals(ResolutionStatus.RESOLVED, result.of(module));
    }
}
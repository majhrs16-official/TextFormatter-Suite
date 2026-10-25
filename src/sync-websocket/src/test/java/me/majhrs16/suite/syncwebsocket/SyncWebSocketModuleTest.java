package me.majhrs16.suite.syncwebsocket;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncWebSocketModuleTest {

    @Test
    void descriptorDeclaresSyncSinkAndWebSocketCapabilities() {
        ModuleDescriptor descriptor = new SyncWebSocketModule().descriptor();

        assertEquals("sync-websocket", descriptor.name());
        assertEquals(SemVer.of(2, 1, 0), descriptor.version());
        assertEquals(SemVer.of(2, 1, 0), descriptor.contractVersion());
        assertEquals(17, descriptor.jvmMin());
        assertEquals(0, descriptor.jvmMax());
        assertTrue(descriptor.provides().contains(Capability.of("sync-sink", SemVer.of(2, 1, 0))));
        assertTrue(descriptor.provides().contains(Capability.of("websocket-server", SemVer.of(2, 1, 0))));
        assertTrue(descriptor.requires().stream()
            .anyMatch(r -> r.name().equals("sync-bus")));
    }
}

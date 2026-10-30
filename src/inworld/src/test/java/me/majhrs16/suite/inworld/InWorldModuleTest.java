package me.majhrs16.suite.inworld;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InWorldModuleTest {

    @Test
    void descriptorDeclaresInWorldAndChannelCapabilities() {
        ModuleDescriptor descriptor = new InWorldModule().descriptor();

        assertEquals("inworld", descriptor.name());
        assertEquals(SemVer.of(2, 1, 0), descriptor.version());
        assertEquals(SemVer.of(2, 1, 0), descriptor.contractVersion());
        assertEquals(17, descriptor.jvmMin());
        assertEquals(0, descriptor.jvmMax());
        assertTrue(descriptor.provides().contains(Capability.of("inworld-signs", SemVer.of(2, 1, 0))));
        assertTrue(descriptor.provides().contains(Capability.of("inworld-containers", SemVer.of(2, 1, 0))));
        assertTrue(descriptor.provides().contains(Capability.of("inworld-books", SemVer.of(2, 1, 0))));
        assertTrue(descriptor.provides().contains(Capability.of("channel-world", SemVer.of(2, 1, 0))));
        assertTrue(descriptor.provides().contains(Capability.of("channel-radius", SemVer.of(2, 1, 0))));
        assertTrue(descriptor.provides().contains(Capability.of("click-hover", SemVer.of(2, 1, 0))));
        assertTrue(descriptor.requires().contains(
            Requirement.atLeast("channel-api", SemVer.of(2, 1, 0))));
    }
}

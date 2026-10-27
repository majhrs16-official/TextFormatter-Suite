package me.majhrs16.suite.iflow.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import org.junit.jupiter.api.Test;

class IfowFirewallModuleTest {

    private final IfowFirewallModule module = new IfowFirewallModule();

    @Test
    void descriptorIdentifiesTheModule() {
        ModuleDescriptor descriptor = module.descriptor();

        assertEquals("iflow-firewall", descriptor.name());
        assertEquals("2.1.0", descriptor.version().toString());
        assertEquals("2.1.0", descriptor.contractVersion().toString());
        assertEquals(17, descriptor.jvmMin());
        assertEquals(0, descriptor.jvmMax());
    }

    @Test
    void providesFirewallWithoutRuntimeRequirements() {
        ModuleDescriptor descriptor = module.descriptor();

        assertTrue(descriptor.requires().isEmpty());
        assertTrue(descriptor.provides().stream()
            .map(Capability::name)
            .anyMatch("firewall"::equals));
        assertTrue(descriptor.provides().stream()
            .noneMatch(capability -> capability.name().equals("router")),
            "el router pertenece a iflow-vm");
    }
}

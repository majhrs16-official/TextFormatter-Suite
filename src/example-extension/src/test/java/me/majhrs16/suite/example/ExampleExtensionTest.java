package me.majhrs16.suite.example;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.extension.ExtensionMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleExtensionTest {

    private final ExampleExtension extension = new ExampleExtension();

    @Test
    void exposesStableIdentityAndVersions() {
        assertEquals("example-extension", extension.id());
        assertEquals("Example Extension", extension.name());
        assertEquals(SemVer.of(1, 0, 0), extension.version());
        assertEquals(SemVer.of(2, 1, 0), extension.requiredCoreApi());
        assertTrue(extension.dependencies().isEmpty());
        assertTrue(extension.requiredCapabilities().isEmpty());
    }

    @Test
    void providesTheDocumentedCapability() {
        assertTrue(extension.providedCapabilities().contains(
            Capability.of("custom-command", SemVer.of(1, 0, 0))
        ));
    }

    @Test
    void metadataAndSchemaAreConsistent() {
        ExtensionMetadata metadata = extension.metadata();

        assertEquals("example-extension", metadata.id());
        assertEquals("Example Extension", metadata.name());
        assertEquals("GPL-3.0", metadata.license());
        assertTrue(metadata.stable());
        assertFalse(metadata.experimental());
        assertTrue(metadata.tags().contains("example"));
        assertTrue(extension.configSchema().contains("\"enabled\""));
    }
}

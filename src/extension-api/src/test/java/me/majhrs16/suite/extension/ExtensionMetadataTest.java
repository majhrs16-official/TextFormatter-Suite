package me.majhrs16.suite.extension;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionMetadataTest {

    @Test
    void builderProvidesStableDefaults() {
        ExtensionMetadata metadata = ExtensionMetadata.builder()
            .id("example")
            .name("Example")
            .build();

        assertEquals("example", metadata.id());
        assertEquals("Example", metadata.name());
        assertEquals("", metadata.description());
        assertEquals("", metadata.author());
        assertEquals("GPL-3.0", metadata.license());
        assertEquals(List.of(), metadata.tags());
        assertEquals(Map.of(), metadata.links());
        assertEquals("2.1.0", metadata.minSuiteVersion());
        assertEquals("", metadata.maxSuiteVersion());
        assertTrue(metadata.stable());
        assertFalse(metadata.experimental());
    }

    @Test
    void builderKeepsExplicitValues() {
        ExtensionMetadata metadata = ExtensionMetadata.builder()
            .id("custom")
            .name("Custom")
            .description("desc")
            .author("author")
            .website("https://example.invalid")
            .license("MIT")
            .tags(List.of("one", "two"))
            .links(Map.of("issues", "https://example.invalid/issues"))
            .minSuiteVersion("2.0.0")
            .maxSuiteVersion("3.0.0")
            .stable(false)
            .experimental(true)
            .build();

        assertEquals("custom", metadata.id());
        assertEquals("desc", metadata.description());
        assertEquals("author", metadata.author());
        assertEquals("MIT", metadata.license());
        assertEquals(List.of("one", "two"), metadata.tags());
        assertEquals("https://example.invalid/issues", metadata.links().get("issues"));
        assertEquals("2.0.0", metadata.minSuiteVersion());
        assertEquals("3.0.0", metadata.maxSuiteVersion());
        assertFalse(metadata.stable());
        assertTrue(metadata.experimental());
    }
}

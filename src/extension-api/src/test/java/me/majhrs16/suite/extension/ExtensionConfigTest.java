package me.majhrs16.suite.extension;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionConfigTest {

    @Test
    void readsTypedValuesWithDefaults() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("string", "value");
        values.put("int", 7);
        values.put("long", 9L);
        values.put("double", 1.5d);
        values.put("bool", "true");
        values.put("list", List.of("a", "b"));
        values.put("map", Map.of("key", "v"));

        ExtensionConfig config = ExtensionConfig.of(values);

        assertTrue(config.has("string"));
        assertFalse(config.has("missing"));
        assertEquals("value", config.getString("string"));
        assertEquals("value", config.getStringOrDefault("string", "fallback"));
        assertEquals("fallback", config.getStringOrDefault("missing", "fallback"));
        assertEquals(7, config.getInt("int"));
        assertEquals(9L, config.getLong("long"));
        assertEquals(1.5d, config.getDouble("double"));
        assertTrue(config.getBoolean("bool"));
        assertEquals(List.of("a", "b"), config.getList("list"));
        assertEquals("v", config.getMap("map").get("key"));
        assertEquals(3, config.getIntOrDefault("missing", 3));
        assertTrue(config.getBooleanOrDefault("missing", true));
    }

    @Test
    void returnsZeroOrEmptyForWrongTypes() {
        ExtensionConfig config = ExtensionConfig.of(Map.of("string", "not-a-number"));

        assertEquals(0, config.getInt("string"));
        assertEquals(0L, config.getLong("string"));
        assertEquals(0.0d, config.getDouble("string"));
        assertTrue(config.getList("missing").isEmpty());
        assertTrue(config.getMap("missing").isEmpty());
    }

    @Test
    void exposesAnUnmodifiableView() {
        ExtensionConfig config = ExtensionConfig.of(Map.of("key", "value"));

        assertEquals(1, config.keys().size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> config.asMap().put("other", "value")
        );
    }

    @Test
    void emptyConfigHasNoKeys() {
        assertTrue(ExtensionConfig.empty().keys().isEmpty());
    }
}

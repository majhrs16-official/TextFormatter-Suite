package me.majhrs16.suite.api.config;

import java.util.Objects;

/**
 * Declarative description of one configuration value.
 *
 * <p>The registry stores metadata, not values. Each module keeps ownership of
 * its own file format and persistence; this record lets the runtime expose a
 * uniform schema for help, validation and future generated commands.</p>
 *
 * @param key          dotted configuration key
 * @param type         expected Java type
 * @param defaultValue default value, or {@code null}
 * @param description  short human-readable description
 */
public record ConfigValue(String key, Class<?> type, Object defaultValue, String description) {

    private static final String KEY = "[A-Za-z0-9][A-Za-z0-9_.-]*";

    public ConfigValue {
        Objects.requireNonNull(key, "key");
        if (!key.matches(KEY)) {
            throw new IllegalArgumentException("config key is invalid: " + key);
        }
        Objects.requireNonNull(type, "type");
        if (defaultValue != null && !type.isInstance(defaultValue)) {
            throw new IllegalArgumentException("default value for " + key
                    + " is not a " + type.getName());
        }
        description = description == null ? "" : description;
    }

    /** @return a value description without a custom description. */
    public static ConfigValue of(String key, Class<?> type, Object defaultValue) {
        return new ConfigValue(key, type, defaultValue, "");
    }
}

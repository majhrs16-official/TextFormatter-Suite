package me.majhrs16.suite.api.config;

import java.util.List;
import java.util.Map;

/**
 * One module-owned configuration section.
 *
 * <p>A section declares its namespace, schema and validation hook. The module
 * still decides where and how values are persisted. The runtime uses the
 * section to expose a uniform surface and to remove it when the module stops.</p>
 */
public interface ConfigSection {

    /** @return the module namespace, unique across the runtime. */
    String namespace();

    /** @return the declared values in display order. */
    List<ConfigValue> values();

    /**
     * Validates current values.
     *
     * <p>The default implementation checks that every present value matches the
     * declared type. Modules with cross-field rules override it and may call
     * this implementation first.</p>
     *
     * @param current current values by key
     * @throws IllegalArgumentException when a value has the wrong type
     */
    default void validate(Map<String, Object> current) {
        for (ConfigValue value : values()) {
            if (!current.containsKey(value.key())) {
                continue;
            }
            Object actual = current.get(value.key());
            if (actual != null && !value.type().isInstance(actual)) {
                throw new IllegalArgumentException("config value " + value.key()
                        + " must be " + value.type().getSimpleName()
                        + ", got " + actual.getClass().getSimpleName());
            }
        }
    }

    /**
     * Reloads the module configuration from its own storage.
     *
     * <p>The runtime never calls this implicitly. Implementations that cannot
     * reload must throw {@link UnsupportedOperationException} explicitly
     * instead of silently doing nothing.</p>
     */
    void reload();
}

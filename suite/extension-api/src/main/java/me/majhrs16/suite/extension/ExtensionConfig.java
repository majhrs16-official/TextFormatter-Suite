package me.majhrs16.suite.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable configuration holder for an extension.
 * <p>
 * Wraps a Map with type-safe getters and validation.
 * </p>
 */
public final class ExtensionConfig {

    private final Map<String, Object> values;

    private ExtensionConfig(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static ExtensionConfig empty() {
        return new ExtensionConfig(Map.of());
    }

    public static ExtensionConfig of(Map<String, Object> values) {
        return new ExtensionConfig(values);
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) values.get(key);
    }

    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) values.getOrDefault(key, defaultValue);
    }

    public String getString(String key) {
        Object v = values.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    public String getStringOrDefault(String key, String defaultValue) {
        Object v = values.get(key);
        return v != null ? String.valueOf(v) : defaultValue;
    }

    public int getInt(String key) {
        Object v = values.get(key);
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    public int getIntOrDefault(String key, int defaultValue) {
        Object v = values.get(key);
        if (v instanceof Number n) return n.intValue();
        return defaultValue;
    }

    public long getLong(String key) {
        Object v = values.get(key);
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    public boolean getBoolean(String key) {
        Object v = values.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    public boolean getBooleanOrDefault(String key, boolean defaultValue) {
        Object v = values.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    public double getDouble(String key) {
        Object v = values.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    public <T> java.util.List<T> getList(String key) {
        Object v = values.get(key);
        if (v instanceof java.util.List<?> l) return (java.util.List<T>) l;
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object v = values.get(key);
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) m;
            return result;
        }
        return Collections.emptyMap();
    }

    public Set<String> keys() {
        return values.keySet();
    }

    @Override
    public String toString() {
        return "ExtensionConfig{" + values + "}";
    }
}
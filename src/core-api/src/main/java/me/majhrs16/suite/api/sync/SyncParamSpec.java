package me.majhrs16.suite.api.sync;

import java.util.Objects;

/**
 * Declarative description of one action parameter or result.
 *
 * @param name     parameter name, non-blank
 * @param type     neutral value type
 * @param required whether the caller must provide it
 * @param item     element description for list-like values; {@code null}
 *                 otherwise
 * @param secret   whether the value must be redacted before it reaches the
 *                 audit trail; the value itself is never logged either way
 */
public record SyncParamSpec(
        String name,
        SyncValueType type,
        boolean required,
        SyncParamSpec item,
        boolean secret
) {

    public SyncParamSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("param name must not be blank");
        }
        Objects.requireNonNull(type, "type");
        if (item != null && item == this) {
            throw new IllegalArgumentException("param item cannot reference itself");
        }
    }

    /** Required scalar parameter. */
    public static SyncParamSpec required(String name, SyncValueType type) {
        return new SyncParamSpec(name, type, true, null, false);
    }

    /** Optional scalar parameter. */
    public static SyncParamSpec optional(String name, SyncValueType type) {
        return new SyncParamSpec(name, type, false, null, false);
    }

    /**
     * Scalar parameter whose value must be redacted before hashing for the
     * audit trail.
     *
     * @param name     parameter name
     * @param type     neutral value type
     * @param required whether the caller must provide it
     * @return a secret parameter spec
     */
    public static SyncParamSpec secret(String name, SyncValueType type, boolean required) {
        return new SyncParamSpec(name, type, required, null, true);
    }
}

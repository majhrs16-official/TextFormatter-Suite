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
 */
public record SyncParamSpec(
        String name,
        SyncValueType type,
        boolean required,
        SyncParamSpec item
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
        return new SyncParamSpec(name, type, true, null);
    }

    /** Optional scalar parameter. */
    public static SyncParamSpec optional(String name, SyncValueType type) {
        return new SyncParamSpec(name, type, false, null);
    }
}

package me.majhrs16.suite.api.sync;

import java.util.Objects;

/**
 * Origin of a sync action invocation.
 *
 * <p>{@link Official} is reserved for the official pipeline and features and
 * may only invoke the least common multiple. {@link Program} identifies a
 * third-party iFlow program by the SHA-256 of its image; it can invoke only the
 * actions explicitly granted by the operator.</p>
 */
public sealed interface Principal {

    /** Official suite code, identified by its module id. */
    record Official(String moduleId) implements Principal {
        public Official {
            if (moduleId == null || moduleId.isBlank()) {
                throw new IllegalArgumentException("moduleId must not be blank");
            }
        }
    }

    /** Third-party iFlow program, identified by the SHA-256 of its image. */
    record Program(String sha256) implements Principal {
        public Program {
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "sha256 must be 64 lowercase hexadecimal characters");
            }
        }
    }
}

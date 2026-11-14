package me.majhrs16.suite.api.sync;

import java.util.Objects;
import java.util.Set;

/**
 * Operator grant for one third-party iFlow program.
 *
 * <p>The grant is indexed by the SHA-256 of the program image, never by its
 * name. {@link #capabilities()} holds exact capability names or trailing
 * wildcards ({@code sync.discord.*}, {@code module.textformatter.*}); the
 * program must declare the exact action in its image and the effective
 * authority is the intersection of both. A missing, ambiguous or unreadable
 * grant is a denial (fail-closed).</p>
 *
 * <p>{@link ActionKind#DESTRUCTIVE} and {@link ActionKind#MODERATION} require
 * {@link #allowDestructive()} to be {@code true}. The remaining quotas are a
 * per-program call budget and a maximum TLV payload size; the action timeout
 * stays in {@link SyncActionSpec#timeoutMillis()}.</p>
 *
 * @param sha256           lowercase SHA-256 of the program image
 * @param capabilities     exact capabilities or trailing {@code .*} patterns
 * @param allowDestructive whether destructive and moderation actions are allowed
 * @param callsPerMinute   maximum authorized calls per rolling minute
 * @param maxPayloadBytes  maximum encoded argument payload size
 */
public record ProgramGrant(
        String sha256,
        Set<String> capabilities,
        boolean allowDestructive,
        int callsPerMinute,
        int maxPayloadBytes
) {

    /** Default call budget when the grant omits it. */
    public static final int DEFAULT_CALLS_PER_MINUTE = 60;

    /** Default payload bound when the grant omits it. */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 65_536;

    public ProgramGrant {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "sha256 must be 64 lowercase hexadecimal characters");
        }
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        for (String capability : capabilities) {
            requirePattern(capability);
        }
        if (callsPerMinute <= 0) {
            throw new IllegalArgumentException("callsPerMinute must be positive");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
    }

    /**
     * @param capability exact capability, e.g. {@code sync.discord.channel.create}
     * @return whether this grant authorizes the capability
     */
    public boolean allows(String capability) {
        if (capability == null || capability.isBlank()) {
            return false;
        }
        for (String pattern : capabilities) {
            if (matches(pattern, capability)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param kind action nature
     * @return whether the grant authorizes this kind of action
     */
    public boolean permits(ActionKind kind) {
        Objects.requireNonNull(kind, "kind");
        return allowDestructive
                || (kind != ActionKind.DESTRUCTIVE && kind != ActionKind.MODERATION);
    }

    /**
     * Matches one grant pattern against one exact capability.
     *
     * <p>The wildcard is only valid as the last segment and matches one or more
     * remaining segments: {@code sync.discord.*} matches
     * {@code sync.discord.channel.create} but not {@code sync.discord}.</p>
     *
     * @param pattern    exact capability or trailing {@code .*} pattern
     * @param capability exact capability declared by the program
     * @return whether the pattern covers the capability
     */
    public static boolean matches(String pattern, String capability) {
        requirePattern(pattern);
        if (pattern.equals(capability)) {
            return true;
        }
        if (!pattern.endsWith(".*")) {
            return false;
        }
        String prefix = pattern.substring(0, pattern.length() - 2);
        return capability.length() > prefix.length() + 1
                && capability.startsWith(prefix + ".");
    }

    private static void requirePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("capability pattern must not be blank");
        }
        if (pattern.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "capability pattern must not contain whitespace: '" + pattern + "'");
        }
        int star = pattern.indexOf('*');
        if (star < 0) {
            return;
        }
        if (star != pattern.length() - 1 || !pattern.endsWith(".*")) {
            throw new IllegalArgumentException(
                    "wildcard is only allowed as a trailing segment: '" + pattern + "'");
        }
        String prefix = pattern.substring(0, pattern.length() - 2);
        if (prefix.isEmpty() || prefix.contains("*") || prefix.startsWith(".")
                || prefix.endsWith(".") || prefix.contains("..")) {
            throw new IllegalArgumentException(
                    "invalid wildcard capability pattern: '" + pattern + "'");
        }
    }
}

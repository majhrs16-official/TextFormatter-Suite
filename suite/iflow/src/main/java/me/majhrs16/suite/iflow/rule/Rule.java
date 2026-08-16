package me.majhrs16.suite.iflow.rule;

import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.iflow.target.PolicyTarget;

import java.util.Objects;
import java.util.Optional;

/**
 * A single firewall rule: a target disposition gated by an optional matcher
 * set. Every matcher narrows the set of {@code (message × recipient)} pairs
 * the rule applies to; an empty matcher set matches everything.
 *
 * <p>Rules are evaluated in priority order (lowest {@code priority} number
 * first); the first matching rule wins. Values come straight from the
 * {@code rules.yml} config of iFlow.</p>
 */
public final class Rule {

    private final String id;
    private final int priority;
    private final PolicyTarget target;
    private final String reason;
    private final String channelPath;   // exact dotted path, or null = any
    private final String emitterPattern; // future-proof: prefix/regex, nullable
    private final String receiverPattern;
    private final Direction.Kind kind;

    private Rule(Builder builder) {
        this.id = builder.id == null ? "" : builder.id;
        this.priority = builder.priority;
        this.target = Objects.requireNonNull(builder.target, "target");
        this.reason = builder.reason;
        this.channelPath = builder.channelPath;
        this.emitterPattern = builder.emitterPattern;
        this.receiverPattern = builder.receiverPattern;
        this.kind = builder.kind;
    }

    public String id() {
        return id;
    }

    public int priority() {
        return priority;
    }

    public PolicyTarget target() {
        return target;
    }

    public String reason() {
        return reason;
    }

    public String channelPath() {
        return channelPath;
    }

    public String emitterPattern() {
        return emitterPattern;
    }

    public String receiverPattern() {
        return receiverPattern;
    }

    public Direction.Kind kind() {
        return kind;
    }

    /** @return whether this rule applies to the given message/recipient pair. */
    public boolean matches(String channel, String emitterName, String receiverName,
                           Direction messageDirection) {
        if (channelPath != null && !channelPath.equals(channel)) {
            return false;
        }
        if (kind != null && messageDirection != null && messageDirection.kind() != kind) {
            return false;
        }
        if (emitterPattern != null && !glob(emitterPattern, emitterName)) {
            return false;
        }
        if (receiverPattern != null && !glob(receiverPattern, receiverName)) {
            return false;
        }
        return true;
    }

    /** Simple {@code *} wildcard match, case-insensitive. */
    private static boolean glob(String pattern, String value) {
        String normalized = pattern.toLowerCase();
        String candidate = value == null ? "" : value.toLowerCase();
        int star = normalized.indexOf('*');
        if (star < 0) {
            return normalized.equals(candidate);
        }
        String prefix = normalized.substring(0, star);
        String suffix = normalized.substring(star + 1);
        return candidate.startsWith(prefix) && candidate.endsWith(suffix)
            && candidate.length() >= prefix.length() + suffix.length();
    }

    public static Builder builder(PolicyTarget target) {
        return new Builder(target);
    }

    public static final class Builder {
        private String id;
        private int priority = Integer.MAX_VALUE;
        private final PolicyTarget target;
        private String reason;
        private String channelPath;
        private String emitterPattern;
        private String receiverPattern;
        private Direction.Kind kind;

        private Builder(PolicyTarget target) {
            this.target = target;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder channelPath(String channelPath) {
            this.channelPath = channelPath;
            return this;
        }

        public Builder emitterPattern(String emitterPattern) {
            this.emitterPattern = emitterPattern;
            return this;
        }

        public Builder receiverPattern(String receiverPattern) {
            this.receiverPattern = receiverPattern;
            return this;
        }

        public Builder direction(Direction.Kind kind) {
            this.kind = kind;
            return this;
        }

        public Rule build() {
            return new Rule(this);
        }
    }
}
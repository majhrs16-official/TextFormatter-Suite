package me.majhrs16.suite.textformatter.channel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Centralized, path-indexed catalog of {@link Channel}s.
 *
 * <p>This is the modern replacement for the flat v4 {@code FormatGroups}
 * registry: channels live on arbitrary dotted paths ({@code chat},
 * {@code private.owner}, {@code discord.global}) and lookup falls back up the
 * path ancestors, so a group can inherit formats from its parent and only
 * override what it needs. The index is immutable after
 * {@link Builder#build()} and to guarantee deterministic rendering.</p>
 */
public final class ChannelRegistry {

    private final Map<String, Channel> channels;

    private ChannelRegistry(Map<String, Channel> channels) {
        Map<String, Channel> copy = new LinkedHashMap<>(channels);
        // Root lookup must resolve a channel named exactly as its full path.
        this.channels = Collections.unmodifiableMap(copy);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return whether a channel exists on the exact dotted path. */
    public boolean has(String path) {
        return channels.containsKey(path);
    }

    /** @return the exact channel, or empty. */
    public Optional<Channel> get(String path) {
        return Optional.ofNullable(channels.get(path));
    }

    /**
     * Resolves the effective channel for a path, walking up the ancestors
     * ({@code a.b.c} → {@code a.b} → {@code a} → root). Falls back to a
     * bare {@code chat} channel when nothing matches.
     */
    public Channel resolve(String path) {
        if (path == null || path.isEmpty()) {
            path = "chat";
        }
        String current = path;
        while (!current.isEmpty()) {
            Channel channel = channels.get(current);
            if (channel != null) {
                return channel;
            }
            int dot = current.lastIndexOf('.');
            if (dot < 0) {
                break;
            }
            current = current.substring(0, dot);
        }
        return channels.getOrDefault("chat", Channel.builder("chat").build());
    }

    /** All registered channels, in insertion order. */
    public Collection<Channel> all() {
        return channels.values();
    }

    /** All dotted paths, in insertion order. */
    public List<String> paths() {
        return new ArrayList<>(channels.keySet());
    }

    public static final class Builder {

        private final Map<String, Channel> channels = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder register(Channel channel) {
            channels.put(channel.name(), channel);
            return this;
        }

        public ChannelRegistry build() {
            return new ChannelRegistry(channels);
        }
    }
}
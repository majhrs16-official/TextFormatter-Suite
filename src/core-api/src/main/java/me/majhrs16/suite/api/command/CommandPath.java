package me.majhrs16.suite.api.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A command path relative to the configured {@link CommandRoot}.
 *
 * <p>An empty path represents the root itself. Modules never include the root
 * in this value; the runtime composes it when it renders usage or resolves a
 * command.</p>
 */
public record CommandPath(List<String> segments) {

    private static final String SEGMENT = "[A-Za-z0-9][A-Za-z0-9_-]*";

    public CommandPath {
        Objects.requireNonNull(segments, "segments");
        List<String> copy = new ArrayList<>(segments.size());
        for (String segment : segments) {
            if (segment == null || !segment.matches(SEGMENT)) {
                throw new IllegalArgumentException("path segment is invalid: " + segment);
            }
            copy.add(segment);
        }
        segments = List.copyOf(copy);
    }

    /** @return the empty path. */
    public static CommandPath empty() {
        return new CommandPath(List.of());
    }

    /** @return a path made of {@code segments}. */
    public static CommandPath of(String... segments) {
        return new CommandPath(List.of(segments));
    }

    /** @return a new path with {@code segment} appended. */
    public CommandPath child(String segment) {
        List<String> copy = new ArrayList<>(segments);
        copy.add(segment);
        return new CommandPath(copy);
    }

    /** @return the path as it appears after the root. */
    public String display() {
        return String.join(" ", segments);
    }
}

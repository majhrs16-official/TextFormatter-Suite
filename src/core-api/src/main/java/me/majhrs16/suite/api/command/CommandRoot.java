package me.majhrs16.suite.api.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Absolute root of the suite command tree.
 *
 * <p>The root is configuration, not module code. A module always registers
 * paths relative to this root, so changing {@code /suite} to
 * {@code /suite module} or {@code /tf} does not require recompiling modules.</p>
 *
 * @param segments canonical root segments, for example {@code [suite]} or
 *                 {@code [suite, module]}
 * @param aliases  alternative roots; each alias is a non-empty segment list
 */
public record CommandRoot(List<String> segments, List<List<String>> aliases) {

    private static final String SEGMENT = "[a-z0-9][a-z0-9_-]*";

    public CommandRoot {
        segments = validate(segments, "root");
        Objects.requireNonNull(aliases, "aliases");
        List<List<String>> normalized = new ArrayList<>();
        for (List<String> alias : aliases) {
            List<String> checked = validate(alias, "alias");
            if (checked.equals(segments)) {
                throw new IllegalArgumentException("alias must differ from root: " + checked);
            }
            normalized.add(checked);
        }
        aliases = List.copyOf(normalized);
    }

    /** Creates a root without aliases. */
    public CommandRoot(List<String> segments) {
        this(segments, List.of());
    }

    /** @return a root made of {@code segments}. */
    public static CommandRoot of(String... segments) {
        return new CommandRoot(List.of(segments));
    }

    /**
     * Parses a whitespace-separated root.
     *
     * @param value root text, for example {@code "suite module"}
     * @return the parsed root
     */
    public static CommandRoot parse(String value) {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("root must not be blank");
        }
        return of(trimmed.split("\\s+"));
    }

    /** @return the default root with its legacy {@code txf} alias. */
    public static CommandRoot suite() {
        return new CommandRoot(List.of("suite"), List.of(List.of("txf")));
    }

    /** @return a copy with the supplied aliases. */
    public CommandRoot withAliases(List<List<String>> aliases) {
        return new CommandRoot(segments, aliases);
    }

    /** @return a copy with one more alias. */
    public CommandRoot withAlias(String... alias) {
        List<List<String>> all = new ArrayList<>(aliases);
        all.add(List.of(alias));
        return new CommandRoot(segments, all);
    }

    /** @return the first segment, which is what the platform registers. */
    public String first() {
        return segments.get(0);
    }

    /** @return the segments after the first one. */
    public List<String> remaining() {
        return segments.subList(1, segments.size());
    }

    /** @return the root as it appears after the slash. */
    public String display() {
        return String.join(" ", segments);
    }

    private static List<String> validate(List<String> values, String label) {
        Objects.requireNonNull(values, label);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || !value.matches(SEGMENT)) {
                throw new IllegalArgumentException(label + " segment is invalid: " + value);
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }
}

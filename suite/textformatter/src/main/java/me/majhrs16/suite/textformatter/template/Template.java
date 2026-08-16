package me.majhrs16.suite.textformatter.template;

import java.util.Objects;

/**
 * An immutable, authored chat template written with the MiniMessage syntax
 * plus the engine's own {@code <tr>...</tr>} translatable spans.
 *
 * <p>Example:</p>
 * <pre>{@code
 *   <gray><bold>%player_name%</bold></gray>
 *   <white><tr><%content%></tr></white>
 * }</pre>
 */
public final class Template {

    private final String source;

    private Template(String source) {
        this.source = source;
    }

    public static Template of(String source) {
        return new Template(Objects.requireNonNull(source, "source"));
    }

    /** @return the raw template string. */
    public String source() {
        return source;
    }

    public boolean isEmpty() {
        return source.isEmpty();
    }

    @Override
    public String toString() {
        return source;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Template && source.equals(((Template) other).source);
    }

    @Override
    public int hashCode() {
        return source.hashCode();
    }
}
package me.majhrs16.suite.api.message;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A pair of parallel arrays holding the translatable texts and their optional
 * format templates.
 *
 * <p>{@code texts[0]} is translated using {@code formats[0]}, {@code texts[1]}
 * with {@code formats[1]}, and so on. An empty formats array means the texts
 * are used literally. Multiple messages are naturally represented by multiple
 * entries, not by a fixed-size from/to pair.</p>
 */
public final class Formats {

    /** Default placeholder consumed by every format template. */
    public static final String PLACEHOLDER = "%ct_messages%";

    private final String[] texts;
    private final String[] formats;

    public Formats(String[] texts, String[] formats) {
        this.texts = texts == null ? new String[0] : texts.clone();
        this.formats = formats == null ? new String[0] : formats.clone();
    }

    public static Formats empty() {
        return new Formats(new String[0], new String[0]);
    }

    public static Formats of(String... texts) {
        return new Formats(texts, new String[0]);
    }

    public int size() {
        return texts.length;
    }

    public String text(int index) {
        return texts[index];
    }

    public String format(int index) {
        return formats.length > index ? formats[index] : PLACEHOLDER;
    }

    /** Raw copy of the texts. */
    public String[] texts() {
        return texts.clone();
    }

    /** Raw copy of the format templates. */
    public String[] formats() {
        return formats.clone();
    }

    public boolean isEmpty() {
        return texts.length == 0;
    }

    /** Tolerated length accessor for scripts. */
    public List<String> textList() {
        return Arrays.asList(texts);
    }

    /** Tolerated length accessor for scripts. */
    public List<String> formatList() {
        return Arrays.asList(formats);
    }

    public Builder toBuilder() {
        return new Builder(texts, formats);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Formats)) {
            return false;
        }
        Formats that = (Formats) other;
        return Arrays.equals(texts, that.texts) && Arrays.equals(formats, that.formats);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(texts), Arrays.hashCode(formats));
    }

    @Override
    public String toString() {
        return "Formats[" + texts.length + "]";
    }

    /** Fluent builder over the parallel arrays. */
    public static final class Builder {

        private String[] texts;
        private String[] formats;

        public Builder() {
            this(new String[0], new String[0]);
        }

        private Builder(String[] texts, String[] formats) {
            this.texts = texts == null ? new String[0] : texts.clone();
            this.formats = formats == null ? new String[0] : formats.clone();
        }

        public Builder texts(String... texts) {
            this.texts = texts == null ? new String[0] : texts.clone();
            return this;
        }

        public Builder formats(String... formats) {
            this.formats = formats == null ? new String[0] : formats.clone();
            return this;
        }

        public Builder text(int index, String text) {
            String[] next = Arrays.copyOf(this.texts, Math.max(this.texts.length, index + 1));
            next[index] = text;
            this.texts = next;
            return this;
        }

        public Builder format(int index, String format) {
            String[] next = Arrays.copyOf(this.formats, Math.max(this.formats.length, index + 1));
            next[index] = format;
            this.formats = next;
            return this;
        }

        public Builder add(String text) {
            String[] next = Arrays.copyOf(this.texts, this.texts.length + 1);
            next[this.texts.length] = text;
            this.texts = next;
            return this;
        }

        public boolean isEmpty() {
            return texts.length == 0 && formats.length == 0;
        }

        public Formats build() {
            return new Formats(texts, formats);
        }
    }
}
package me.majhrs16.cht.core.template;

import me.majhrs16.cht.core.message.SoundSpec;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * The set of templates used to render one {@code ChatMessageType}.
 *
 * <p>All fields are optional except {@code message}; a null tooltip means no
 * hover is attached, a null sound means nothing is played.</p>
 */
public final class FormatSpec {

    private final Template message;
    private final Template tooltip;
    private final SoundSpec sound;

    private FormatSpec(Template message, Template tooltip, SoundSpec sound) {
        this.message = Objects.requireNonNull(message, "message");
        this.tooltip = tooltip;
        this.sound = sound;
    }

    public static Builder builder(Template message) {
        return new Builder(message);
    }

    public Template message() {
        return message;
    }

    @Nullable
    public Template tooltip() {
        return tooltip;
    }

    @Nullable
    public SoundSpec sound() {
        return sound;
    }

    public static final class Builder {

        private final Template message;
        private Template tooltip;
        private SoundSpec sound;

        private Builder(Template message) {
            this.message = message;
        }

        public Builder tooltip(Template tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public Builder sound(SoundSpec sound) {
            this.sound = sound;
            return this;
        }

        public FormatSpec build() {
            return new FormatSpec(message, tooltip, sound);
        }
    }
}
package me.majhrs16.suite.iflow.rule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * Transform operations applied to a message before delivery.
 * <p>
 * Transforms are executed in order after a rule matches but before the
 * disposition decision. They modify the message content, sounds, or add delays.
 * </p>
 * <p>
 * Supported operations (F7+):
 * <ul>
 *   <li>{@link Rewrite} — replace the message template/text</li>
 *   <li>{@link Sounds} — add/remove sound specs</li>
 *   <li>{@link Sleep} — delay delivery by milliseconds</li>
 *   <li>{@link SetLangSource} — override source language</li>
 *   <li>{@link SetLangTarget} — override target language</li>
 *   <li>{@link SetColorMode} — override color mode</li>
 *   <li>{@link SetFormatPapi} — enable/disable PAPI placeholders</li>
 *   <li>{@link SetChannel} — change the channel path (redirect)</li>
 * </ul>
 * </p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Rewrite.class, name = "rewrite"),
    @JsonSubTypes.Type(value = Sounds.class, name = "sounds"),
    @JsonSubTypes.Type(value = Sleep.class, name = "sleep"),
    @JsonSubTypes.Type(value = SetLangSource.class, name = "setLangSource"),
    @JsonSubTypes.Type(value = SetLangTarget.class, name = "setLangTarget"),
    @JsonSubTypes.Type(value = SetColorMode.class, name = "setColorMode"),
    @JsonSubTypes.Type(value = SetFormatPapi.class, name = "setFormatPapi"),
    @JsonSubTypes.Type(value = SetChannel.class, name = "setChannel")
})
public abstract class TransformOp {

    /**
     * Applies this transform to the given message via ScriptSurface.
     *
     * @param surface the script surface exposing message mutation operations
     */
    public abstract void apply(ScriptSurface surface);

    /**
     * Rewrites the message template.
     * <p>
     * YAML example:
     * <pre>
     * - op: rewrite
     *   template: "<green>💬 %content%</green>"
     * </pre>
     */
    public static final class Rewrite extends TransformOp {
        private final String template;

        @JsonCreator
        public Rewrite(@JsonProperty("template") String template) {
            this.template = template;
        }

        public String template() {
            return template;
        }

        @Override
        public void apply(ScriptSurface surface) {
            surface.msg().setText(template);
        }

        @Override
        public String toString() {
            return "Rewrite(template=" + template + ")";
        }
    }

    /**
     * Adds or removes sound specifications.
     * <p>
     * YAML example:
     * <pre>
     * - op: sounds
     *   add: [ping-message.mp3, notification.ogg]
     *   remove: [alert.wav]
     * </pre>
     */
    public static final class Sounds extends TransformOp {
        private final List<String> add;
        private final List<String> remove;

        @JsonCreator
        public Sounds(
            @JsonProperty("add") List<String> add,
            @JsonProperty("remove") List<String> remove
        ) {
            this.add = add != null ? add : List.of();
            this.remove = remove != null ? remove : List.of();
        }

        public List<String> add() {
            return add;
        }

        public List<String> remove() {
            return remove;
        }

        @Override
        public void apply(ScriptSurface surface) {
            // Sounds are handled at channel level; this marks for post-processing
            if (!add.isEmpty()) {
                surface.msg().setSoundsAdd(add);
            }
            if (!remove.isEmpty()) {
                surface.msg().setSoundsRemove(remove);
            }
        }

        @Override
        public String toString() {
            return "Sounds(add=" + add + ", remove=" + remove + ")";
        }
    }

    /**
     * Delays delivery by a specified number of milliseconds.
     * <p>
     * YAML example:
     * <pre>
     * - op: sleep
     *   millis: 1500
     * </pre>
     */
    public static final class Sleep extends TransformOp {
        private final long millis;

        @JsonCreator
        public Sleep(@JsonProperty("millis") long millis) {
            this.millis = millis;
        }

        public long millis() {
            return millis;
        }

        @Override
        public void apply(ScriptSurface surface) {
            surface.msg().setSleepMillis(millis);
        }

        @Override
        public String toString() {
            return "Sleep(millis=" + millis + ")";
        }
    }

    /**
     * Sets the source language for translation.
     */
    public static final class SetLangSource extends TransformOp {
        private final String lang;

        @JsonCreator
        public SetLangSource(@JsonProperty("lang") String lang) {
            this.lang = lang;
        }

        public String lang() {
            return lang;
        }

        @Override
        public void apply(ScriptSurface surface) {
            surface.setLangSource(me.majhrs16.suite.api.message.Language.of(lang).orElseThrow());
        }

        @Override
        public String toString() {
            return "SetLangSource(lang=" + lang + ")";
        }
    }

    /**
     * Sets the target language for translation.
     */
    public static final class SetLangTarget extends TransformOp {
        private final String lang;

        @JsonCreator
        public SetLangTarget(@JsonProperty("lang") String lang) {
            this.lang = lang;
        }

        public String lang() {
            return lang;
        }

        @Override
        public void apply(ScriptSurface surface) {
            surface.setLangTarget(me.majhrs16.suite.api.message.Language.of(lang).orElseThrow());
        }

        @Override
        public String toString() {
            return "SetLangTarget(lang=" + lang + ")";
        }
    }

    /**
     * Sets the color mode for the message.
     */
    public static final class SetColorMode extends TransformOp {
        private final String mode;

        @JsonCreator
        public SetColorMode(@JsonProperty("mode") String mode) {
            this.mode = mode;
        }

        public String mode() {
            return mode;
        }

        @Override
        public void apply(ScriptSurface surface) {
            surface.setColorMode(mode);
        }

        @Override
        public String toString() {
            return "SetColorMode(mode=" + mode + ")";
        }
    }

    /**
     * Enables or disables PAPI placeholder resolution.
     */
    public static final class SetFormatPapi extends TransformOp {
        private final boolean enabled;

        @JsonCreator
        public SetFormatPapi(@JsonProperty("enabled") boolean enabled) {
            this.enabled = enabled;
        }

        public boolean enabled() {
            return enabled;
        }

        @Override
        public void apply(ScriptSurface surface) {
            surface.setFormatPapi(enabled);
        }

        @Override
        public String toString() {
            return "SetFormatPapi(enabled=" + enabled + ")";
        }
    }

    /**
     * Changes the channel path (redirect to different channel).
     */
    public static final class SetChannel extends TransformOp {
        private final String channelPath;

        @JsonCreator
        public SetChannel(@JsonProperty("channel") String channelPath) {
            this.channelPath = channelPath;
        }

        public String channelPath() {
            return channelPath;
        }

        @Override
        public void apply(ScriptSurface surface) {
            surface.setFormat(channelPath);
        }

        @Override
        public String toString() {
            return "SetChannel(channel=" + channelPath + ")";
        }
    }
}
package me.majhrs16.cht.core.message;

import me.majhrs16.cht.core.language.Language;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * The atomic, dissectable unit that travels through the routing engine.
 *
 * <p>This is the replacement for the historic {@code ChatMessage}: a single
 * delivery unit with its own sender, routing direction, content arrays,
 * formatting group, colors, sound and language pair. It is deliberately
 * mutable through the {@link Builder} API and always cloned before being
 * mutated by a rule, so a rule can never corrupt a message shared by other
 * recipients.</p>
 *
 * <p>There is no embedded from/to. The {@link Direction} tells the engine which
 * audience this particular unit targets; a chat event therefore produces as
 * many {@code Message}s as audiences it wants to reach, each with their own
 * format group ({@code remitente_*} for the initiator, {@code destinatario_*}
 * for the rest).</p>
 */
public final class Message {

    private final UUID id;
    private final MessageType type;
    private final Actor sender;
    private final Direction direction;
    private final Formats messages;
    private final Formats toolTips;
    private final String[] sounds;
    private final ColorMode colorMode;
    private final Language langSource;
    private final Language langTarget;
    private final boolean translate;
    private final boolean cancelled;
    private final boolean show;
    private final boolean formatPapi;
    private final String lastFormatPath;

    private Message(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID();
        this.type = builder.type != null ? builder.type : MessageType.CUSTOM;
        this.sender = builder.sender != null ? builder.sender : Actor.unknown("UNKNOWN");
        this.direction = builder.direction != null ? builder.direction : Direction.others();
        this.messages = builder.messages != null ? builder.messages : Formats.empty();
        this.toolTips = builder.toolTips != null ? builder.toolTips : Formats.empty();
        this.sounds = builder.sounds == null ? new String[0] : builder.sounds.clone();
        this.colorMode = builder.colorMode != null ? builder.colorMode : ColorMode.BY_PERMISSION;
        this.langSource = builder.langSource != null ? builder.langSource : Language.AUTO;
        this.langTarget = builder.langTarget != null ? builder.langTarget : Language.AUTO;
        this.translate = builder.translate;
        this.cancelled = builder.cancelled;
        this.show = builder.show;
        this.formatPapi = builder.formatPapi;
        this.lastFormatPath = builder.lastFormatPath;
    }

    public UUID id() {
        return id;
    }

    public MessageType type() {
        return type;
    }

    public Actor sender() {
        return sender;
    }

    public Direction direction() {
        return direction;
    }

    public Formats messages() {
        return messages;
    }

    public Formats toolTips() {
        return toolTips;
    }

    public String[] sounds() {
        return sounds.clone();
    }

    public ColorMode colorMode() {
        return colorMode;
    }

    public Language langSource() {
        return langSource;
    }

    public Language langTarget() {
        return langTarget;
    }

    public boolean shouldTranslate() {
        return translate;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isShown() {
        return show;
    }

    public boolean formatPapi() {
        return formatPapi;
    }

    /** @return the format group path that built this message, if any. */
    public String lastFormatPath() {
        return lastFormatPath;
    }

    /** The first text of the first message; convenience for scripting. */
    public String text() {
        return messages.isEmpty() ? "" : messages.text(0);
    }

    /** All texts as a plain list; convenience for scripting. */
    public String[] texts() {
        return messages.texts();
    }

    public Message.Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public String toString() {
        return "Message{" + type + " from=" + sender.name()
            + " dir=" + direction + " texts=" + messages.size() + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder over the atomic message. Every mutation returns a fresh
     * message instance via {@link #build()}.
     */
    public static final class Builder {

        private UUID id;
        private MessageType type;
        private Actor sender;
        private Direction direction;
        private Formats messages;
        private Formats toolTips;
        private String[] sounds;
        private ColorMode colorMode;
        private Language langSource;
        private Language langTarget;
        private boolean translate = true;
        private boolean cancelled;
        private boolean show = true;
        private boolean formatPapi = true;
        private String lastFormatPath;

        public Builder() {
        }

        private Builder(Message original) {
            this.id = original.id;
            this.type = original.type;
            this.sender = original.sender;
            this.direction = original.direction;
            this.messages = original.messages;
            this.toolTips = original.toolTips;
            this.sounds = original.sounds();
            this.colorMode = original.colorMode;
            this.langSource = original.langSource;
            this.langTarget = original.langTarget;
            this.translate = original.translate;
            this.cancelled = original.cancelled;
            this.show = original.show;
            this.formatPapi = original.formatPapi;
            this.lastFormatPath = original.lastFormatPath;
        }

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public Builder sender(Actor sender) {
            this.sender = sender;
            return this;
        }

        public Builder direction(Direction direction) {
            this.direction = direction;
            return this;
        }

        public Builder messages(Formats messages) {
            this.messages = messages;
            return this;
        }

        public Builder texts(String... texts) {
            Formats current = this.messages == null ? Formats.empty() : this.messages;
            this.messages = new Formats.Builder()
                .texts(texts)
                .formats(current.formats())
                .build();
            return this;
        }

        public Builder text(String text) {
            return texts(text);
        }

        public Builder toolTips(Formats toolTips) {
            this.toolTips = toolTips;
            return this;
        }

        public Builder sounds(String... sounds) {
            this.sounds = sounds;
            return this;
        }

        public Builder colorMode(ColorMode colorMode) {
            this.colorMode = colorMode;
            return this;
        }

        public Builder langSource(Language langSource) {
            this.langSource = langSource;
            return this;
        }

        public Builder langTarget(Language langTarget) {
            this.langTarget = langTarget;
            return this;
        }

        public Builder translate(boolean translate) {
            this.translate = translate;
            return this;
        }

        public Builder cancelled(boolean cancelled) {
            this.cancelled = cancelled;
            return this;
        }

        public Builder show(boolean show) {
            this.show = show;
            return this;
        }

        public Builder formatPapi(boolean formatPapi) {
            this.formatPapi = formatPapi;
            return this;
        }

        public Builder lastFormatPath(String lastFormatPath) {
            this.lastFormatPath = lastFormatPath;
            return this;
        }

        public Message build() {
            return new Message(this);
        }
    }
}
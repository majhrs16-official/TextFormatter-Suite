package me.majhrs16.suite.api.message;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The atomic, dissectable unit that travels through the routing engine.
 *
 * <p>A single delivery unit with its own sender, routing direction, content
 * arrays, channel, colors, sound and language pair. <strong>Immutable</strong>
 * through the {@link Builder} API; mutations produce a new {@code Message}
 * instance via {@code withX()} methods, so a rule can never corrupt a message
 * shared by other recipients.</p>
 *
 * <p>There is no embedded from/to. The {@link Direction} tells the engine which
 * audience this particular unit targets; a chat event therefore produces as
 * many {@code Message}s as audiences it wants to reach.</p>
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
    private final Language resolvedSourceLanguage;
    private final boolean translate;
    private final boolean cancelled;
    private final boolean show;
    private final boolean formatPapi;
    private final String channel;
    private final long sleepMillis;

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
        this.resolvedSourceLanguage = builder.resolvedSourceLanguage;
        this.translate = builder.translate;
        this.cancelled = builder.cancelled;
        this.show = builder.show;
        this.formatPapi = builder.formatPapi;
        this.channel = builder.channel;
        this.sleepMillis = builder.sleepMillis;
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

    /** @return the resolved source language, or null if not yet resolved. */
    public Language resolvedSourceLanguage() {
        return resolvedSourceLanguage;
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

    /** @return the channel path this message was routed through, if any. */
    public String channel() {
        return channel;
    }

    /** @return sleep milliseconds for delayed delivery (F7+ sleep transform). */
    public long sleepMillis() {
        return sleepMillis;
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

    public Message withResolvedSourceLanguage(Language resolvedSourceLanguage) {
        return toBuilder().resolvedSourceLanguage(resolvedSourceLanguage).build();
    }

    public Message withLangTarget(Language langTarget) {
        return toBuilder().langTarget(langTarget).build();
    }

    public Message withLangSource(Language langSource) {
        return toBuilder().langSource(langSource).build();
    }

    public Message withTranslate(boolean translate) {
        return toBuilder().translate(translate).build();
    }

    public Message withChannel(String channel) {
        return toBuilder().channel(channel).build();
    }

    public Message withCancelled(boolean cancelled) {
        return toBuilder().cancelled(cancelled).build();
    }

    public Message withText(String text) {
        return toBuilder().text(text).build();
    }

    public Message withColorMode(ColorMode colorMode) {
        return toBuilder().colorMode(colorMode).build();
    }

    public Message withFormatPapi(boolean formatPapi) {
        return toBuilder().formatPapi(formatPapi).build();
    }

    public Message withProcessed(boolean processed) {
        return toBuilder().show(processed).build();
    }

    public Message withSoundsAdd(List<String> sounds) {
        String[] current = this.sounds;
        String[] combined = Arrays.copyOf(current, current.length + sounds.size());
        for (int i = 0; i < sounds.size(); i++) {
            combined[current.length + i] = sounds.get(i);
        }
        return toBuilder().sounds(combined).build();
    }

    public Message withSoundsRemove(List<String> soundsToRemove) {
        String[] current = this.sounds;
        List<String> filtered = Arrays.stream(current)
            .filter(s -> !soundsToRemove.contains(s))
            .toList();
        return toBuilder().sounds(filtered.toArray(new String[0])).build();
    }

    public Message withSleepMillis(long millis) {
        return toBuilder().sleepMillis(millis).build();
    }

    public Message clone() {
        return new Message(toBuilder());
    }

    public String toJson() {
        return toString();
    }

    @Override
    public String toString() {
        return "Message{" + type + " from=" + sender.name()
            + " dir=" + direction + " texts=" + messages.size() + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder over the atomic message. */
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
        private Language resolvedSourceLanguage;
        private boolean translate = true;
        private boolean cancelled;
        private boolean show = true;
        private boolean formatPapi = true;
        private String channel;
        private long sleepMillis;

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
            this.resolvedSourceLanguage = original.resolvedSourceLanguage;
            this.translate = original.translate;
            this.cancelled = original.cancelled;
            this.show = original.show;
            this.formatPapi = original.formatPapi;
            this.channel = original.channel;
            this.sleepMillis = original.sleepMillis;
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

        public Builder resolvedSourceLanguage(Language resolvedSourceLanguage) {
            this.resolvedSourceLanguage = resolvedSourceLanguage;
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

        public Builder channel(String channel) {
            this.channel = channel;
            return this;
        }

        public Builder sleepMillis(long sleepMillis) {
            this.sleepMillis = sleepMillis;
            return this;
        }

        public Builder from(Message message) {
            if (message == null) return this;
            this.id = message.id;
            this.type = message.type;
            this.sender = message.sender;
            this.direction = message.direction;
            this.messages = message.messages;
            this.toolTips = message.toolTips;
            this.sounds = message.sounds();
            this.colorMode = message.colorMode;
            this.langSource = message.langSource;
            this.langTarget = message.langTarget;
            this.resolvedSourceLanguage = message.resolvedSourceLanguage;
            this.translate = message.translate;
            this.cancelled = message.cancelled;
            this.show = message.show;
            this.formatPapi = message.formatPapi;
            this.channel = message.channel;
            this.sleepMillis = message.sleepMillis;
            return this;
        }

        public Message build() {
            return new Message(this);
        }
    }
}
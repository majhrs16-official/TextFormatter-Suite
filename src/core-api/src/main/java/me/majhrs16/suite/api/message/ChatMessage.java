package me.majhrs16.suite.api.message;

/**
 * Represents a chat message in the suite ecosystem.
 * <p>
 * This is the canonical domain model for chat messages, replacing duplicated
 * versions in {@code common-legacy} and {@code coretranslator}.
 * </p>
 * <p>
 * Thread-safe and immutable. Instances should be created via {@link Builder}.
 */
public final class ChatMessage {

    /** The sender/initiator of the message. */
    private final Actor sender;

    /** The type/category of the message. */
    private final ChatMessageType type;

    /** Optional qualifier (e.g. permission, world name, radius). */
    private final String qualifier;

    /** Explicit recipients, only meaningful for {@link ChatMessageType#SPECIFIC}. */
    private final Actor[] recipients;

    private ChatMessage(Builder builder) {
        this.sender = builder.sender;
        this.type = builder.type;
        this.qualifier = builder.qualifier;
        this.recipients = builder.recipients == null ? null : builder.recipients.clone();
    }

    public Actor sender() {
        return sender;
    }

    public ChatMessageType type() {
        return type;
    }

    public String qualifier() {
        return qualifier;
    }

    public Actor[] recipients() {
        return recipients == null ? new Actor[0] : recipients.clone();
    }

    /** @return whether this is a chat message (as opposed to console, etc.). */
    public boolean isChat() {
        return type == ChatMessageType.CHAT;
    }

    /** @return whether this message targets a specific recipient set. */
    public boolean isSpecific() {
        return type == ChatMessageType.SPECIFIC;
    }

    /** Human-friendly description. */
    @Override
    public String toString() {
        return "ChatMessage[type=" + type + ", sender=" + sender + "]";
    }

    /** Builder for {@link ChatMessage}. */
    public static final class Builder {
        private Actor sender;
        private ChatMessageType type;
        private String qualifier;
        private Actor[] recipients;

        private Builder() {
        }

        public Builder sender(Actor sender) {
            this.sender = sender;
            return this;
        }

        public Builder type(ChatMessageType type) {
            this.type = type;
            return this;
        }

        public Builder qualifier(String qualifier) {
            this.qualifier = qualifier;
            return this;
        }

        public Builder recipients(Actor... recipients) {
            this.recipients = recipients;
            return this;
        }

        public ChatMessage build() {
            return new ChatMessage(this);
        }
    }

    /** Pre-defined message types. */
    public enum ChatMessageType {
        /** In-game chat with rich formatting. */
        CHAT,

        /** Server console output. */
        CONSOLE,

        /** An external platform, e.g. Discord. */
        DISCORD,

        /** A player only via private messaging (tell). */
        PRIVATE,

        /** Explicit recipient set. */
        SPECIFIC
    }
}
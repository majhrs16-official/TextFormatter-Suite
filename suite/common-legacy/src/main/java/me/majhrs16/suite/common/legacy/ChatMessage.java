package me.majhrs16.suite.common.legacy;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deprecated minimal copy of the v1.8 {@code ChatMessage}, self-contained so
 * the CoreTranslator bridge can be built without the legacy jar. It is
 * deliberately not type-compatible with the old plugin class; it only carries
 * the same payload shape, using suite types ({@link Actor}, {@link Language})
 * in place of the legacy {@code Subject}/{@code Language}.
 *
 * <p>Intended to be produced/consumed exclusively by
 * {@link me.majhrs16.suite.coretranslator.LegacyBridge}.</p>
 */
@Deprecated
public final class ChatMessage {

    private final UUID id;
    private final ChatMessageType type;
    private final Actor sender;
    private final Actor target;
    private final String content;
    private final Optional<Language> sourceLanguage;
    private final Instant timestamp;
    private final Map<String, String> context;
    private final boolean cancelled;
    private final boolean cancelledForSender;
    private final boolean translate;

    private ChatMessage(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID();
        this.type = Objects.requireNonNull(builder.type, "type");
        this.sender = Objects.requireNonNull(builder.sender, "sender");
        this.target = builder.target;
        this.content = builder.content == null ? "" : builder.content;
        this.sourceLanguage = Optional.ofNullable(builder.sourceLanguage);
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.context = Collections.unmodifiableMap(new LinkedHashMap<>(builder.context));
        this.cancelled = builder.cancelled;
        this.cancelledForSender = builder.cancelledForSender;
        this.translate = builder.translate;
    }

    public UUID id() {
        return id;
    }

    public ChatMessageType type() {
        return type;
    }

    public Actor sender() {
        return sender;
    }

    public Optional<Actor> target() {
        return Optional.ofNullable(target);
    }

    public String content() {
        return content;
    }

    public Optional<Language> sourceLanguage() {
        return sourceLanguage;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public Map<String, String> context() {
        return context;
    }

    public String context(String key) {
        return context.get(key);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isCancelledForSender() {
        return cancelledForSender;
    }

    public boolean shouldTranslate() {
        return translate;
    }

    public static Builder builder(ChatMessageType type, Actor sender) {
        return new Builder(type, sender);
    }

    /** Fluent builder mirroring the legacy one. */
    public static final class Builder {

        private final ChatMessageType type;
        private final Actor sender;
        private UUID id;
        private Actor target;
        private String content;
        private Language sourceLanguage;
        private Instant timestamp;
        private final Map<String, String> context = new LinkedHashMap<>();
        private boolean cancelled;
        private boolean cancelledForSender;
        private boolean translate = true;

        private Builder(ChatMessageType type, Actor sender) {
            this.type = type;
            this.sender = sender;
        }

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder target(Actor target) {
            this.target = target;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder sourceLanguage(Language sourceLanguage) {
            this.sourceLanguage = sourceLanguage;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder context(String key, String value) {
            this.context.put(key, value);
            return this;
        }

        public Builder cancelled(boolean cancelled) {
            this.cancelled = cancelled;
            return this;
        }

        public Builder cancelledForSender(boolean cancelledForSender) {
            this.cancelledForSender = cancelledForSender;
            return this;
        }

        public Builder translate(boolean translate) {
            this.translate = translate;
            return this;
        }

        public ChatMessage build() {
            return new ChatMessage(this);
        }
    }
}

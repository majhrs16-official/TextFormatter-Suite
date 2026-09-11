package me.majhrs16.suite.api.event;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.spi.PluginLogger;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Event fired by the MessageDispatcher before rules and rendering.
 * <p>
 * Third-party plugins can register listeners to intercept, modify, or cancel
 * messages before they are processed by iFlow and the formatter.
 * </p>
 * <p>
 * Listeners run on the dispatch thread (same as the incoming event).
 * Operations must be fast; avoid blocking I/O.
 * </p>
 */
public final class MessageEvent {

    private final Message message;
    private final Actor sender;
    private final boolean cancelled;
    private final UUID id;
    private Message modifiedMessage;

    public MessageEvent(Message message, Actor sender) {
        this.message = Objects.requireNonNull(message, "message");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.id = UUID.randomUUID();
        this.cancelled = false;
    }

    public Message message() {
        return message;
    }

    public Actor sender() {
        return sender;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public UUID id() {
        return id;
    }

    /** @return the modified message if set, otherwise the original. */
    public Message getMessage() {
        return modifiedMessage != null ? modifiedMessage : message;
    }

    /** Replaces the message that will continue through the pipeline. */
    public void setMessage(Message modifiedMessage) {
        this.modifiedMessage = modifiedMessage;
    }

    /** Cancels the event; the message will not be processed further. */
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /** @return a unique event ID for correlation/debugging. */
    public UUID eventId() {
        return id;
    }
}
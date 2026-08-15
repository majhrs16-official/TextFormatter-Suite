package me.majhrs16.cht.core.event;

import me.majhrs16.cht.core.message.Message;

/**
 * Neutral, cancellable carrier handed to external integrations.
 *
 * <p>Fired synchronously by the {@code ChatRouter} for every unit that enters
 * the pipeline, before any rules or rendering run. An integration may:
 *
 * <ul>
 *   <li>{@link #setCancelled(boolean) cancel} the message to drop it entirely,</li>
 *   <li>{@link #setMessage(Message) replace} it with a modified unit, or</li>
 *   <li>{@link #setProcessed(boolean) mark} it as processed, meaning the
 *       integration took over delivery and the router must not display it.</li>
 * </ul>
 */
public final class MessageEvent {

    private Message message;
    private boolean cancelled;
    private boolean processed;

    public MessageEvent(Message message) {
        this.message = message;
    }

    /** @return the message currently carried by the event. */
    public Message message() {
        return message;
    }

    /** Replaces the carried message. */
    public void setMessage(Message message) {
        this.message = message;
    }

    /** @return {@code true} when the pipeline must drop the message. */
    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /** @return {@code true} when an integration handled delivery. */
    public boolean isProcessed() {
        return processed;
    }

    /** Marks the message as handled; the router then skips its own delivery. */
    public void setProcessed(boolean processed) {
        this.processed = processed;
    }
}

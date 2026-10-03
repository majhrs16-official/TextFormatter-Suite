package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Message;

/**
 * A bidirectional edge connector that bridges Minecraft chat to an external
 * platform (Discord, Telegram, HTTP webhook, raw TCP/UDP).
 *
 * <p>Lifecycle is explicit ({@link #start()}/{@link #stop()}); outbound
 * deliveries go through {@link #send(Message)}; inbound events are pushed back
 * to the engine by the platform task invoking {@link SyncListener}. Modules
 * advertising the {@code sync-sink} capability must provide an implementation
 * bound to this contract.</p>
 */
public interface SyncSink {

    /** Delivery reliability semantics for this sink. */
    enum DeliverySemantics {
        /** Best-effort, no ordering, no retransmission (e.g., UDP). */
        BEST_EFFORT,
        /** Ordered, at-least-once with retries (e.g., TCP, WebSocket, HTTP). */
        AT_LEAST_ONCE,
        /** Exactly-once with deduplication (e.g., Velocity with acks). */
        EXACTLY_ONCE
    }

    /** Ordering guarantee for this sink. */
    enum Ordering {
        /** No ordering guarantee. */
        NONE,
        /** Messages delivered in send order per channel. */
        PER_CHANNEL,
        /** Globally ordered across all channels. */
        GLOBAL
    }

    /** @return a stable connector id, e.g. {@code discord} or {@code webhook}. */
    String name();

    /** @return delivery reliability semantics for this transport. */
    DeliverySemantics deliverySemantics();

    /** @return ordering guarantee for this transport. */
    Ordering ordering();

    /** Connects to the remote and arms the inbound listener. */
    void start() throws Exception;

    /** Disconnects and releases platform resources. */
    void stop();

    /** Sends a message outbound to the remote platform. */
    void send(Message message) throws Exception;

    /**
     * Registers the inbound callback. Exactly one listener is supported;
     * replacing it atomically swaps the handler.
     */
    void setListener(SyncListener listener);
}
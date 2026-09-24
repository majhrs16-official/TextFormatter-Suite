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

    /** @return a stable connector id, e.g. {@code discord} or {@code webhook}. */
    String name();

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
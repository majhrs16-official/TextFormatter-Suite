package me.majhrs16.suite.api.velocity;

import java.util.List;

/**
 * Transport to a Velocity proxy, implemented by a Velocity adapter.
 *
 * <p>The neutral sync logic builds plugin-message payloads and decides which
 * servers receive them. The adapter owns the Velocity API: channel
 * registration, event listeners and {@code sendPluginMessage}. Implementations
 * must not block module activation on the proxy network.</p>
 */
public interface VelocityConnection extends AutoCloseable {

    /**
     * Registers the plugin-message channel and the inbound listener.
     *
     * @param listener callback for payloads received on the suite channel
     * @throws Exception if the adapter cannot register its channel/listener
     */
    void start(VelocityListener listener) throws Exception;

    /** Unregisters the channel/listener. Idempotent. */
    void stop();

    /** @return names of the servers currently known to the proxy. */
    List<String> servers();

    /**
     * Sends a raw plugin-message payload to one server.
     *
     * @param server  target server name
     * @param payload encoded suite payload
     * @return {@code true} when the proxy accepted the message
     */
    boolean send(String server, byte[] payload);

    /** Releases adapter resources. Idempotent. */
    @Override
    default void close() {
        stop();
    }
}

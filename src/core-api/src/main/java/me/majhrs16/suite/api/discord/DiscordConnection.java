package me.majhrs16.suite.api.discord;

/**
 * Outbound/inbound connection to Discord, implemented by a technology adapter.
 *
 * <p>Implementations must not block {@link #connect()} waiting for the remote
 * gateway. They connect asynchronously and report readiness through
 * {@link #isConnected()}; {@link #send(String)} fails fast while the connection
 * is not ready. This keeps package activation independent from the network.</p>
 */
public interface DiscordConnection extends AutoCloseable {

    /**
     * Starts the connection without waiting for the remote gateway.
     *
     * @throws Exception if the adapter cannot even schedule the connection
     */
    void connect() throws Exception;

    /** @return {@code true} when outbound sends can be attempted. */
    boolean isConnected();

    /**
     * Sends plain text to the configured channel.
     *
     * @param text message body
     * @throws Exception if the connection is not ready or the send fails
     */
    void send(String text) throws Exception;

    /**
     * Registers the inbound callback. Exactly one listener is supported.
     *
     * @param listener listener, or {@code null} to clear it
     */
    void setListener(DiscordListener listener);

    /** Closes the connection and releases adapter resources. Idempotent. */
    @Override
    void close();
}

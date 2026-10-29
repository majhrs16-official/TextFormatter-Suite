package me.majhrs16.suite.api.discord;

/**
 * Inbound callback implemented by the neutral Discord sink.
 */
public interface DiscordListener {

    /**
     * Called for each accepted message from the configured channel.
     *
     * @param message translated message
     */
    void onMessage(DiscordMessage message);

    /**
     * Called when the adapter loses the connection.
     *
     * @param reason human-readable reason
     */
    default void onDisconnect(String reason) {
    }
}

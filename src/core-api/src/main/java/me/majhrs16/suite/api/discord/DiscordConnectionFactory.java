package me.majhrs16.suite.api.discord;

import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Factory for a concrete {@link DiscordConnection} technology.
 *
 * <p>Adapters register one factory under this type. The neutral
 * {@code sync-discord} package selects the configured factory by
 * {@link #name()} and never imports JDA or any other Discord SDK.</p>
 */
public interface DiscordConnectionFactory {

    /** @return stable adapter name, for example {@code jda} or {@code jdk}. */
    String name();

    /**
     * Creates a connection. The adapter owns the token lifecycle and must
     * wipe any mutable copy when the connection closes.
     *
     * @param settings connection settings
     * @param logger   module logger
     * @return a connection that is not started yet
     */
    DiscordConnection create(DiscordSettings settings, PluginLogger logger);
}

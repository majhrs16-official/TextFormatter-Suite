package me.majhrs16.suite.api.velocity;

import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Factory for a Velocity transport adapter.
 *
 * <p>Adapters register one factory under this type. The neutral
 * {@code sync-velocity} package selects it by {@link #name()} and never imports
 * {@code com.velocitypowered}.</p>
 */
public interface VelocityConnectionFactory {

    /** @return stable adapter name, for example {@code velocity}. */
    String name();

    /**
     * Creates a connection that is not started yet.
     *
     * @param settings channel and subchannel names
     * @param logger   module logger
     * @return adapter connection
     */
    VelocityConnection create(VelocitySettings settings, PluginLogger logger);
}

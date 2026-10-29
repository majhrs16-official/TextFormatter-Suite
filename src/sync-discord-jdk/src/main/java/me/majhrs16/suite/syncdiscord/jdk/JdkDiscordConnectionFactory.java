package me.majhrs16.suite.syncdiscord.jdk;

import me.majhrs16.suite.api.discord.DiscordConnection;
import me.majhrs16.suite.api.discord.DiscordConnectionFactory;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.transport.HttpTransport;

/**
 * Factory for the zero-dependency JDK Discord adapter.
 */
public final class JdkDiscordConnectionFactory implements DiscordConnectionFactory {

    @Override
    public String name() {
        return "jdk";
    }

    @Override
    public DiscordConnection create(DiscordSettings settings, PluginLogger logger) {
        return new JdkDiscordConnection(settings, logger, new HttpTransport());
    }
}

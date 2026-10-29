package me.majhrs16.suite.syncdiscord.jda;

import me.majhrs16.suite.api.discord.DiscordConnection;
import me.majhrs16.suite.api.discord.DiscordConnectionFactory;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.spi.PluginLogger;

/** Factory for the JDA Discord transport. */
public final class JdaDiscordConnectionFactory implements DiscordConnectionFactory {

    @Override
    public String name() {
        return "jda";
    }

    @Override
    public DiscordConnection create(DiscordSettings settings, PluginLogger logger) {
        return new JdaDiscordConnection(settings, logger);
    }
}

package me.majhrs16.suite.syncdiscord.jda;

import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.spi.PluginLogger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdaDiscordConnectionTest {

    private static final PluginLogger LOGGER = new PluginLogger() {
        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable failure) { }
        @Override public void debug(String message, Object... args) { }
    };

    @Test
    void factoryExposesJdaName() {
        JdaDiscordConnectionFactory factory = new JdaDiscordConnectionFactory();

        assertEquals("jda", factory.name());
        assertTrue(factory.create(new DiscordSettings("tok", 42), LOGGER)
            instanceof JdaDiscordConnection);
    }

    @Test
    void blankTokenIsRejectedBeforeAnyNetworkAccess() {
        assertThrows(IllegalArgumentException.class,
            () -> new JdaDiscordConnection(new DiscordSettings("  ", 42), LOGGER));
    }

    @Test
    void sendBeforeConnectFailsFast() {
        JdaDiscordConnection connection =
            new JdaDiscordConnection(new DiscordSettings("tok", 42), LOGGER);

        assertFalse(connection.isConnected());
        assertThrows(IllegalStateException.class, () -> connection.send("hola"));
    }

    @Test
    void closeIsIdempotentAndBlocksLaterSends() {
        JdaDiscordConnection connection =
            new JdaDiscordConnection(new DiscordSettings("tok", 42), LOGGER);

        connection.close();
        connection.close();

        assertFalse(connection.isConnected());
        assertThrows(IllegalStateException.class, () -> connection.send("hola"));
    }
}

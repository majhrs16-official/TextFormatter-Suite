package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.discord.DiscordConnection;
import me.majhrs16.suite.api.discord.DiscordConnectionFactory;
import me.majhrs16.suite.api.discord.DiscordListener;
import me.majhrs16.suite.api.discord.DiscordMessage;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordSinkTest {

    private static final PluginLogger LOGGER = new PluginLogger() {
        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable failure) { }
        @Override public void debug(String message, Object... args) { }
    };

    private static class FakeConnection implements DiscordConnection {
        DiscordListener listener;
        boolean connected;
        boolean closed;
        final List<String> sent = new ArrayList<>();

        @Override public void connect() { connected = true; }
        @Override public boolean isConnected() { return connected && !closed; }
        @Override public void send(String text) { sent.add(text); }
        @Override public void setListener(DiscordListener listener) { this.listener = listener; }
        @Override public void close() { closed = true; connected = false; }
    }

    private static final class FakeFactory implements DiscordConnectionFactory {
        final FakeConnection connection;
        String name = "fake";

        FakeFactory(FakeConnection connection) {
            this.connection = connection;
        }

        @Override public String name() { return name; }
        @Override public DiscordConnection create(DiscordSettings settings, PluginLogger logger) {
            return connection;
        }
    }

    private static final class RecordingListener implements SyncListener {
        final List<Message> messages = new ArrayList<>();
        final List<String> disconnects = new ArrayList<>();

        @Override public void onMessage(SyncSink sink, Message message) { messages.add(message); }
        @Override public void onDisconnect(SyncSink sink, String reason) { disconnects.add(reason); }
    }

    private static Message outbound(String text) {
        return Message.builder().text(text).channel("discord").build();
    }

    @Test
    void startConnectsTheAdapterAndStopClosesIt() throws Exception {
        FakeConnection connection = new FakeConnection();
        DiscordSink sink = new DiscordSink(new FakeFactory(connection),
            new DiscordSettings("tok", 42), LOGGER);

        sink.start();
        assertTrue(connection.connected);
        assertEquals("discord", sink.name());

        sink.stop();
        assertTrue(connection.closed);
        assertFalse(connection.connected);
    }

    @Test
    void sendDelegatesToTheAdapter() throws Exception {
        FakeConnection connection = new FakeConnection();
        DiscordSink sink = new DiscordSink(new FakeFactory(connection),
            new DiscordSettings("tok", 42), LOGGER);
        sink.start();

        sink.send(outbound("hola"));

        assertEquals(List.of("hola"), connection.sent);
    }

    @Test
    void sendBeforeStartFails() {
        FakeConnection connection = new FakeConnection();
        DiscordSink sink = new DiscordSink(new FakeFactory(connection),
            new DiscordSettings("tok", 42), LOGGER);

        assertThrows(IllegalStateException.class, () -> sink.send(outbound("x")));
        assertTrue(connection.sent.isEmpty());
    }

    @Test
    void inboundMessagesAreTranslatedToTheSuiteContract() throws Exception {
        FakeConnection connection = new FakeConnection();
        DiscordSink sink = new DiscordSink(new FakeFactory(connection),
            new DiscordSettings("tok", 42), LOGGER);
        RecordingListener listener = new RecordingListener();
        sink.setListener(listener);
        sink.start();

        connection.listener.onMessage(new DiscordMessage("LUNA", "hola discord", 42));

        assertEquals(1, listener.messages.size());
        Message message = listener.messages.get(0);
        assertEquals("hola discord", message.text());
        assertEquals("LUNA", message.sender().name());
        assertEquals("discord", message.channel());
    }

    @Test
    void disconnectIsForwardedToTheSuiteListener() throws Exception {
        FakeConnection connection = new FakeConnection();
        DiscordSink sink = new DiscordSink(new FakeFactory(connection),
            new DiscordSettings("tok", 42), LOGGER);
        RecordingListener listener = new RecordingListener();
        sink.setListener(listener);
        sink.start();

        connection.listener.onDisconnect("gateway closed");

        assertEquals(List.of("gateway closed"), listener.disconnects);
    }

    @Test
    void startFailureClosesTheAdapterAndDoesNotMarkItStarted() {
        FakeConnection connection = new FakeConnection() {
            @Override public void connect() {
                throw new IllegalStateException("boom");
            }
        };
        DiscordSink sink = new DiscordSink(new FakeFactory(connection),
            new DiscordSettings("tok", 42), LOGGER);

        assertThrows(IllegalStateException.class, sink::start);
        assertTrue(connection.closed);
        assertThrows(IllegalStateException.class, () -> sink.send(outbound("x")));
    }

    @Test
    void configDefaultsToJdaTransportAndReadsOverrides() {
        assertEquals(DiscordConfig.DEFAULT_TRANSPORT,
            DiscordConfig.loadFromConfigDir(java.nio.file.Path.of("/nonexistent")).transport());
    }
}

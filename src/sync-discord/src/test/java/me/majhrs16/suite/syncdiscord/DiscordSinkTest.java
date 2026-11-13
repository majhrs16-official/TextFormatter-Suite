package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.discord.DiscordConnection;
import me.majhrs16.suite.api.discord.DiscordConnectionFactory;
import me.majhrs16.suite.api.discord.DiscordListener;
import me.majhrs16.suite.api.discord.DiscordMessage;
import me.majhrs16.suite.api.discord.DiscordOperation;
import me.majhrs16.suite.api.discord.DiscordResult;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        final List<DiscordOperation> operations = new ArrayList<>();
        DiscordResult next = new DiscordResult.Ok(Map.of());
        RuntimeException failure;

        @Override public void connect() { connected = true; }
        @Override public boolean isConnected() { return connected && !closed; }
        @Override public void send(String text) { sent.add(text); }
        @Override public DiscordResult execute(DiscordOperation operation) {
            operations.add(operation);
            if (failure != null) {
                throw failure;
            }
            return next;
        }
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

    private static final String PROGRAM = "0123456789abcdef".repeat(4);

    private static SyncCall nativeCall(String action, Map<String, Object> args) {
        return new SyncCall("discord", action, args,
            new Principal.Program(PROGRAM), "key", "test");
    }

    private static DiscordSink startedSink(FakeConnection connection) throws Exception {
        DiscordSink sink = new DiscordSink(new FakeFactory(connection),
            new DiscordSettings("tok", 42), LOGGER);
        sink.start();
        return sink;
    }

    @Test
    void surfacePublishesTheLcmAndTheNativeDiscordActions() {
        DiscordSink sink = new DiscordSink(
            new FakeFactory(new FakeConnection()),
            new DiscordSettings("tok", 42), LOGGER);

        SyncSurface surface = sink.surface();

        assertEquals("discord", surface.backend());
        assertTrue(surface.hasLeastCommonMultiple());
        for (String action : List.of(
                "channel.create", "channel.delete", "message.delete",
                "member.ban", "role.assign")) {
            assertTrue(surface.action(action).isPresent(), action);
        }
    }

    @Test
    void channelCreateIsExecutedByTheAdapter() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.next = new DiscordResult.Ok(Map.of("id", "99", "name", "general"));
        DiscordSink sink = startedSink(connection);

        SyncResult result = sink.invoke(nativeCall("channel.create",
            Map.of("guild", "7", "name", "general", "topic", "chat")));

        SyncResult.Ok ok = assertInstanceOf(SyncResult.Ok.class, result);
        assertEquals(Map.of("id", "99", "name", "general"), ok.value());
        assertEquals(List.of(new DiscordOperation.CreateChannel("7", "general", "chat")),
            connection.operations);
    }

    @Test
    void nativeActionsAreDeniedToTheOfficialPrincipalWithoutEffect() throws Exception {
        FakeConnection connection = new FakeConnection();
        DiscordSink sink = startedSink(connection);
        SyncCall call = new SyncCall("discord", "channel.create",
            Map.of("guild", "7", "name", "general"),
            new Principal.Official("suite.pipeline"), "", "test");

        SyncResult result = sink.invoke(call);

        assertInstanceOf(SyncResult.Denied.class, result);
        assertTrue(connection.operations.isEmpty());
    }

    @Test
    void unknownActionIsUnsupportedAndNeverReachesTheAdapter() throws Exception {
        FakeConnection connection = new FakeConnection();
        DiscordSink sink = startedSink(connection);

        SyncResult result = sink.invoke(nativeCall("channel.explode", Map.of()));

        assertInstanceOf(SyncResult.Unsupported.class, result);
        assertTrue(connection.operations.isEmpty());
    }

    @Test
    void missingOrInvalidNativeArgumentsAreReportedAsInvalidArgument() throws Exception {
        FakeConnection connection = new FakeConnection();
        DiscordSink sink = startedSink(connection);

        assertInstanceOf(SyncResult.InvalidArgument.class,
            sink.invoke(nativeCall("channel.create", Map.of("guild", "7"))));
        assertInstanceOf(SyncResult.InvalidArgument.class,
            sink.invoke(nativeCall("channel.create",
                Map.of("guild", "not-a-snowflake", "name", "general"))));
        assertInstanceOf(SyncResult.InvalidArgument.class,
            sink.invoke(nativeCall("message.delete", Map.of("channel", "42"))));
        assertInstanceOf(SyncResult.InvalidArgument.class,
            sink.invoke(nativeCall("member.ban",
                Map.of("guild", "7", "user", "9", "deleteMessageSeconds", -1))));
        assertTrue(connection.operations.isEmpty());
    }

    @Test
    void adapterFailureAndPlatformResultsMapToExplicitSyncResults() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.failure = new IllegalStateException("adapter boom");
        DiscordSink sink = startedSink(connection);

        assertInstanceOf(SyncResult.HostFailure.class,
            sink.invoke(nativeCall("message.delete",
                Map.of("channel", "42", "message", "99"))));

        connection.failure = null;
        connection.next = new DiscordResult.NotFound("missing message");
        assertInstanceOf(SyncResult.NotFound.class,
            sink.invoke(nativeCall("message.delete",
                Map.of("channel", "42", "message", "99"))));

        connection.next = new DiscordResult.Forbidden("missing permissions");
        assertInstanceOf(SyncResult.HostFailure.class,
            sink.invoke(nativeCall("message.delete",
                Map.of("channel", "42", "message", "99"))));

        connection.next = new DiscordResult.Conflict("state changed");
        assertInstanceOf(SyncResult.Conflict.class,
            sink.invoke(nativeCall("message.delete",
                Map.of("channel", "42", "message", "99"))));

        connection.next = new DiscordResult.RateLimited(1_500);
        SyncResult.RateLimited limited = assertInstanceOf(SyncResult.RateLimited.class,
            sink.invoke(nativeCall("message.delete",
                Map.of("channel", "42", "message", "99"))));
        assertEquals(1_500, limited.retryAfterMillis());

        connection.next = new DiscordResult.Failed("transport down");
        assertInstanceOf(SyncResult.HostFailure.class,
            sink.invoke(nativeCall("message.delete",
                Map.of("channel", "42", "message", "99"))));
    }

    @Test
    void nativeActionBeforeStartFailsWithoutReachingTheAdapter() {
        FakeConnection connection = new FakeConnection();
        DiscordSink sink = new DiscordSink(new FakeFactory(connection),
            new DiscordSettings("tok", 42), LOGGER);

        SyncResult result = sink.invoke(nativeCall("channel.create",
            Map.of("guild", "7", "name", "general")));

        assertInstanceOf(SyncResult.HostFailure.class, result);
        assertTrue(connection.operations.isEmpty());
    }

    @Test
    void lcmStillWorksThroughInvoke() throws Exception {
        FakeConnection connection = new FakeConnection();
        DiscordSink sink = startedSink(connection);
        SyncCall call = new SyncCall("discord", "message.send",
            Map.of("message", outbound("hola")),
            new Principal.Official("suite.pipeline"), "key", "test");

        SyncResult result = sink.invoke(call);

        assertInstanceOf(SyncResult.Ok.class, result);
        assertEquals(List.of("hola"), connection.sent);
    }
}

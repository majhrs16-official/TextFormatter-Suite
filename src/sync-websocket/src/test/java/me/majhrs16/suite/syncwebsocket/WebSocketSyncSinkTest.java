package me.majhrs16.suite.syncwebsocket;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketSyncSinkTest {

    private static final PluginLogger LOGGER = new PluginLogger() {
        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable failure) { }
        @Override public void debug(String message, Object... args) { }
    };

    private static final Principal PROGRAM =
        new Principal.Program("0123456789abcdef".repeat(4));

    private static SyncCall nativeCall(String action, Map<String, Object> args) {
        return new SyncCall("websocket", action, args, PROGRAM, "", "test");
    }

    private static Message message(String text) {
        return Message.builder()
            .sender(Actor.unknown("Steve"))
            .direction(Direction.others())
            .type(MessageType.CHAT)
            .text(text)
            .channel("chat")
            .build();
    }

    private static WebSocketSyncSink started() throws IOException {
        WebSocketSyncSink sink = new WebSocketSyncSink(0, "secret", LOGGER);
        sink.start();
        return sink;
    }

    private static TestClient connect(WebSocketSyncSink sink, String path) throws Exception {
        TestClient client = new TestClient(URI.create(
            "ws://127.0.0.1:" + sink.boundPort() + path + "?token=secret"));
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "client did not connect");
        assertTrue(client.open.await(5, TimeUnit.SECONDS), "client did not open");
        return client;
    }

    private static void subscribe(TestClient client, String path) throws Exception {
        client.send("{\"action\":\"subscribe\",\"path\":\"" + path + "\"}");
        assertTrue(client.subscribed.await(5, TimeUnit.SECONDS),
            "client did not subscribe to " + path);
        client.messages.clear();
    }

    private static void close(TestClient client) throws Exception {
        client.closeBlocking();
    }

    @Test
    void publishesLeastCommonMultipleAndNativeActions() {
        WebSocketSyncSink sink = new WebSocketSyncSink(0, "secret", LOGGER);
        try {
            SyncSurface surface = sink.surface();

            assertEquals("websocket", surface.backend());
            assertTrue(surface.hasLeastCommonMultiple());
            assertEquals(ActionKind.WRITE,
                surface.action("path.broadcast").orElseThrow().kind());
            assertEquals(ActionKind.READ,
                surface.action("client.info").orElseThrow().kind());
        } finally {
            sink.stop();
        }
    }

    @Test
    void pathBroadcastReachesSubscribers() throws Exception {
        WebSocketSyncSink sink = started();
        TestClient client = connect(sink, "/ws/chat");
        try {
            subscribe(client, "/ws/chat");

            SyncResult result = sink.invoke(nativeCall("path.broadcast", Map.of(
                "path", "/ws/chat",
                "payload", Map.of("event", "deploy", "id", 7))));

            SyncResult.Ok ok = assertInstanceOf(SyncResult.Ok.class, result);
            Map<?, ?> value = (Map<?, ?>) ok.value();
            assertEquals("/ws/chat", value.get("path"));
            assertEquals(1, value.get("delivered"));

            String received = client.messages.poll(5, TimeUnit.SECONDS);
            assertTrue(received != null, "client did not receive the broadcast");
            assertTrue(received.contains("\"type\":\"broadcast\""), received);
            assertTrue(received.contains("\"path\":\"/ws/chat\""), received);
            assertTrue(received.contains("\"event\":\"deploy\""), received);
            assertTrue(received.contains("\"id\":7"), received);
        } finally {
            close(client);
            sink.stop();
        }
    }

    @Test
    void clientInfoReportsCountersWithoutSecrets() throws Exception {
        WebSocketSyncSink sink = started();
        TestClient client = connect(sink, "/ws/chat");
        try {
            subscribe(client, "/ws/chat");

            SyncResult all = sink.invoke(nativeCall("client.info", Map.of()));
            SyncResult one = sink.invoke(nativeCall("client.info", Map.of("path", "/ws/chat")));

            String summary = String.valueOf(assertInstanceOf(SyncResult.Ok.class, all).value());
            String single = String.valueOf(assertInstanceOf(SyncResult.Ok.class, one).value());
            assertTrue(summary.contains("clients=1"), summary);
            assertTrue(summary.contains("/ws/chat=1"), summary);
            assertTrue(single.contains("path=/ws/chat"), single);
            assertTrue(single.contains("subscribers=1"), single);
            assertFalse(summary.contains("secret"), summary);
            assertFalse(summary.contains("127.0.0.1"), summary);
        } finally {
            close(client);
            sink.stop();
        }
    }

    @Test
    void sendRoutesChatMessagesToChatSubscribers() throws Exception {
        WebSocketSyncSink sink = started();
        TestClient client = connect(sink, "/ws/chat");
        try {
            subscribe(client, "/ws/chat");

            sink.send(message("hola"));

            String received = client.messages.poll(5, TimeUnit.SECONDS);
            assertTrue(received != null, "client did not receive the message");
            assertTrue(received.contains("\"texts\":[\"hola\"]"), received);
        } finally {
            close(client);
            sink.stop();
        }
    }

    @Test
    void officialPrincipalCannotInvokeNativeActions() {
        WebSocketSyncSink sink = new WebSocketSyncSink(0, "secret", LOGGER);
        try {
            SyncResult result = sink.invoke(new SyncCall(
                "websocket", "path.broadcast",
                Map.of("path", "/ws/chat", "payload", Map.of()),
                new Principal.Official("sync-bus"), "", "test"));

            assertInstanceOf(SyncResult.Denied.class, result);
        } finally {
            sink.stop();
        }
    }

    @Test
    void unsupportedActionIsExplicit() {
        WebSocketSyncSink sink = new WebSocketSyncSink(0, "secret", LOGGER);
        try {
            SyncResult result = sink.invoke(nativeCall("channel.create", Map.of()));

            assertInstanceOf(SyncResult.Unsupported.class, result);
        } finally {
            sink.stop();
        }
    }

    @Test
    void invalidArgumentsAreRejectedWithoutNetwork() throws Exception {
        WebSocketSyncSink sink = started();
        try {
            SyncResult missingPath = sink.invoke(nativeCall(
                "path.broadcast", Map.of("payload", Map.of())));
            SyncResult blankPath = sink.invoke(nativeCall(
                "path.broadcast", Map.of("path", " ", "payload", Map.of())));
            SyncResult logsPath = sink.invoke(nativeCall(
                "path.broadcast", Map.of("path", "/ws/logs", "payload", Map.of())));
            SyncResult missingPayload = sink.invoke(nativeCall(
                "path.broadcast", Map.of("path", "/ws/chat")));
            SyncResult wrongPayload = sink.invoke(nativeCall(
                "path.broadcast", Map.of("path", "/ws/chat", "payload", "text")));
            SyncResult unsupportedValue = sink.invoke(nativeCall(
                "path.broadcast", Map.of(
                    "path", "/ws/chat",
                    "payload", Map.of("bad", new Object()))));
            SyncResult invalidInfo = sink.invoke(nativeCall(
                "client.info", Map.of("path", "/nope")));

            assertInstanceOf(SyncResult.InvalidArgument.class, missingPath);
            assertInstanceOf(SyncResult.InvalidArgument.class, blankPath);
            assertInstanceOf(SyncResult.InvalidArgument.class, logsPath);
            assertInstanceOf(SyncResult.InvalidArgument.class, missingPayload);
            assertInstanceOf(SyncResult.InvalidArgument.class, wrongPayload);
            assertInstanceOf(SyncResult.InvalidArgument.class, unsupportedValue);
            assertInstanceOf(SyncResult.InvalidArgument.class, invalidInfo);
        } finally {
            sink.stop();
        }
    }

    @Test
    void stoppedSinkRejectsNativeActionsAndSend() {
        WebSocketSyncSink sink = new WebSocketSyncSink(0, "secret", LOGGER);

        SyncResult broadcast = sink.invoke(nativeCall(
            "path.broadcast", Map.of("path", "/ws/chat", "payload", Map.of())));
        SyncResult info = sink.invoke(nativeCall("client.info", Map.of()));

        assertInstanceOf(SyncResult.HostFailure.class, broadcast);
        assertInstanceOf(SyncResult.HostFailure.class, info);
        assertThrows(IOException.class, () -> sink.send(message("detenido")));
    }

    @Test
    void missingTokenFailsStartExplicitly() {
        WebSocketSyncSink sink = new WebSocketSyncSink(0, " ", LOGGER);

        assertThrows(IOException.class, sink::start);
        assertFalse(sink.isRunning());
    }

    @Test
    void restartsAfterStop() throws Exception {
        WebSocketSyncSink sink = started();
        try {
            int firstPort = sink.boundPort();
            assertTrue(firstPort > 0);

            sink.stop();
            assertFalse(sink.isRunning());

            sink.start();

            assertTrue(sink.isRunning());
            assertTrue(sink.boundPort() > 0);
        } finally {
            sink.stop();
        }
    }

    private static final class TestClient extends WebSocketClient {

        private final CountDownLatch open = new CountDownLatch(1);
        private final CountDownLatch subscribed = new CountDownLatch(1);
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        private TestClient(URI uri) {
            super(uri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            open.countDown();
        }

        @Override
        public void onMessage(String message) {
            messages.add(message);
            if (message.contains("\"type\":\"subscribed\"")) {
                subscribed.countDown();
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
        }
    }
}

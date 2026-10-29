package me.majhrs16.suite.syncdiscord.jdk;

import me.majhrs16.suite.api.discord.DiscordListener;
import me.majhrs16.suite.api.discord.DiscordMessage;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.transport.Transport;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkDiscordConnectionTest {

    private static final PluginLogger LOGGER = new PluginLogger() {
        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable failure) { }
        @Override public void debug(String message, Object... args) { }
    };

    private static final class StubTransport implements Transport {
        final List<String> urls = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        String response = "{\"id\":\"m1\",\"channel_id\":\"42\"}";

        @Override
        public String post(String url, Map<String, String> headers, String jsonBody) {
            urls.add(url);
            this.headers.add(headers);
            bodies.add(jsonBody);
            return response;
        }

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException("get not used");
        }

        @Override
        public String post(String url, String jsonBody) {
            return post(url, Map.of(), jsonBody);
        }
    }

    private static JdkDiscordConnection connection(StubTransport transport, String gatewayUrl) {
        return new JdkDiscordConnection(
            "https://discord.com/api/v10", gatewayUrl, "abc", 42,
            JdkDiscordConnection.INTENT_GUILD_MESSAGES
                | JdkDiscordConnection.INTENT_MESSAGE_CONTENT,
            transport, LOGGER);
    }

    @Test
    void sendPostsToChannelWithBotToken() throws Exception {
        StubTransport transport = new StubTransport();
        JdkDiscordConnection connection = connection(transport, "wss://gateway.invalid");

        connection.send("hola discord");

        assertEquals(1, transport.urls.size());
        String url = transport.urls.get(0);
        assertTrue(url.endsWith("/channels/42/messages"), url);
        assertEquals("Bot abc", transport.headers.get(0).get("Authorization"));
        assertTrue(transport.bodies.get(0).contains("\"content\":\"hola discord\""),
            transport.bodies.get(0));
    }

    @Test
    void sendThrowsWhenResponseHasApiErrorCodeOrNoId() {
        StubTransport transport = new StubTransport();
        transport.response = "{\"code\":50035,\"message\":\"Invalid Form Body\"}";
        JdkDiscordConnection connection = connection(transport, "wss://x");

        assertThrows(IllegalStateException.class, () -> connection.send("x"));
    }

    @Test
    void gatewayDeliversInboundMessageAndHeartbeats() throws Exception {
        WsServer server = new WsServer();
        server.pushAfterReady(new JSONObject()
            .put("op", 0).put("s", 2).put("t", "MESSAGE_CREATE")
            .put("d", new JSONObject()
                .put("id", "m1").put("channel_id", "42")
                .put("author", new JSONObject().put("id", "9").put("username", "LUNA"))
                .put("content", "hola discord")));
        server.start();

        JdkDiscordConnection connection = connection(
            new StubTransport(),
            "ws://127.0.0.1:" + server.port() + "/?v=10&encoding=json");
        CopyOnWriteArrayList<DiscordMessage> received = new CopyOnWriteArrayList<>();
        connection.setListener(new DiscordListener() {
            @Override
            public void onMessage(DiscordMessage message) {
                received.add(message);
            }
        });

        try {
            connection.connect();

            long deadline = System.currentTimeMillis() + 5000;
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            assertEquals(1, received.size(),
                "received=" + received + " serverReceived=" + server.received()
                    + " heartbeats=" + server.heartbeats()
                    + " serverFailure=" + server.failure());
            assertEquals("hola discord", received.get(0).content());
            assertEquals("LUNA", received.get(0).author());
            assertEquals(42L, received.get(0).channelId());

            while (server.heartbeats() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(server.heartbeats() >= 1,
                "gateway must heartbeat on the Hello interval");

            boolean identified = server.received().stream()
                .anyMatch(body -> body.contains("\"op\":2"));
            assertTrue(identified, "gateway must send Identify after Hello");
        } finally {
            connection.close();
            server.close();
        }
    }

    @Test
    void connectReturnsWithoutWaitingForTheGateway() throws Exception {
        WsServer server = new WsServer();
        server.start();
        JdkDiscordConnection connection = connection(
            new StubTransport(),
            "ws://127.0.0.1:" + server.port() + "/?v=10&encoding=json");

        try {
            long started = System.nanoTime();
            connection.connect();
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertTrue(elapsedMillis < 1000,
                "connect() must not block on the gateway; took " + elapsedMillis + "ms");
            assertTrue(connection.isConnected());
            connection.send("hola");
        } finally {
            connection.close();
            server.close();
        }
    }

    @Test
    void sendAfterCloseFailsInsteadOfSendingAnEmptyToken() throws Exception {
        StubTransport transport = new StubTransport();
        JdkDiscordConnection connection = connection(transport, "wss://gateway.invalid");

        connection.close();

        assertThrows(IllegalStateException.class, () -> connection.send("x"));
        assertTrue(transport.urls.isEmpty(), "the request must not reach the transport");
    }

    @Test
    void factoryCreatesJdkConnection() {
        JdkDiscordConnectionFactory factory = new JdkDiscordConnectionFactory();

        assertEquals("jdk", factory.name());
        assertTrue(factory.create(new DiscordSettings("tok", 42), LOGGER)
            instanceof JdkDiscordConnection);
    }
}

package me.majhrs16.suite.syncdiscord.jdk;

import me.majhrs16.suite.api.discord.DiscordListener;
import me.majhrs16.suite.api.discord.DiscordMessage;
import me.majhrs16.suite.api.discord.DiscordOperation;
import me.majhrs16.suite.api.discord.DiscordResult;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.transport.Transport;
import me.majhrs16.suite.transport.TransportException;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        final List<String> methods = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        String response = "{\"id\":\"m1\",\"channel_id\":\"42\"}";
        IOException failure;
        boolean patchSupported = true;

        @Override
        public String patch(String url, Map<String, String> headers, String jsonBody)
                throws IOException {
            methods.add("PATCH");
            if (failure != null) {
                throw failure;
            }
            if (!patchSupported) {
                throw new UnsupportedOperationException("PATCH is not supported by this transport");
            }
            urls.add(url);
            this.headers.add(headers);
            bodies.add(jsonBody == null ? "" : jsonBody);
            return response;
        }

        @Override
        public String post(String url, Map<String, String> headers, String jsonBody)
                throws IOException {
            methods.add("POST");
            if (failure != null) {
                throw failure;
            }
            urls.add(url);
            this.headers.add(headers);
            bodies.add(jsonBody);
            return response;
        }

        @Override
        public String put(String url, Map<String, String> headers, String jsonBody)
                throws IOException {
            methods.add("PUT");
            if (failure != null) {
                throw failure;
            }
            urls.add(url);
            this.headers.add(headers);
            bodies.add(jsonBody == null ? "" : jsonBody);
            return response;
        }

        @Override
        public String delete(String url, Map<String, String> headers) throws IOException {
            methods.add("DELETE");
            if (failure != null) {
                throw failure;
            }
            urls.add(url);
            this.headers.add(headers);
            bodies.add("");
            return response;
        }

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException("get not used");
        }

        @Override
        public String post(String url, String jsonBody) {
            try {
                return post(url, Map.of(), jsonBody);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
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

    @Test
    void createChannelPostsToTheGuildAndReturnsTheCreatedId() throws Exception {
        StubTransport transport = new StubTransport();
        transport.response = "{\"id\":\"c1\",\"name\":\"general\"}";
        JdkDiscordConnection connection = connection(transport, "wss://gateway.invalid");

        DiscordResult result = connection.execute(
            new DiscordOperation.CreateChannel("7", "general", "chat"));

        DiscordResult.Ok ok = assertInstanceOf(DiscordResult.Ok.class, result);
        assertEquals("c1", ok.value().get("id"));
        assertEquals("general", ok.value().get("name"));
        assertEquals("POST", transport.methods.get(0));
        assertTrue(transport.urls.get(0).endsWith("/guilds/7/channels"),
            transport.urls.get(0));
        assertTrue(transport.bodies.get(0).contains("\"name\":\"general\""),
            transport.bodies.get(0));
        assertTrue(transport.bodies.get(0).contains("\"topic\":\"chat\""),
            transport.bodies.get(0));
        assertEquals("Bot abc", transport.headers.get(0).get("Authorization"));
    }

    @Test
    void deleteMessageUsesDeleteAndTheAuditReason() throws Exception {
        StubTransport transport = new StubTransport();
        JdkDiscordConnection connection = connection(transport, "wss://gateway.invalid");

        DiscordResult result = connection.execute(
            new DiscordOperation.DeleteMessage("42", "99", "clean up"));

        assertInstanceOf(DiscordResult.Ok.class, result);
        assertEquals("DELETE", transport.methods.get(0));
        assertTrue(transport.urls.get(0).endsWith("/channels/42/messages/99"),
            transport.urls.get(0));
        assertEquals("clean%20up",
            transport.headers.get(0).get("X-Audit-Log-Reason"));
    }

    @Test
    void editMessagePatchesTheMessageAndReturnsTheId() throws Exception {
        StubTransport transport = new StubTransport();
        transport.response = "{\"id\":\"99\",\"content\":\"editado\"}";
        JdkDiscordConnection connection = connection(transport, "wss://gateway.invalid");

        DiscordResult result = connection.execute(
            new DiscordOperation.EditMessage("42", "99", "editado"));

        DiscordResult.Ok ok = assertInstanceOf(DiscordResult.Ok.class, result);
        assertEquals(Map.of("id", "99"), ok.value());
        assertEquals("PATCH", transport.methods.get(0));
        assertTrue(transport.urls.get(0).endsWith("/channels/42/messages/99"),
            transport.urls.get(0));
        assertTrue(transport.bodies.get(0).contains("\"content\":\"editado\""),
            transport.bodies.get(0));
        assertEquals("Bot abc", transport.headers.get(0).get("Authorization"));
    }

    @Test
    void editMessageFailsExplicitlyWithoutPatchSupport() throws Exception {
        StubTransport transport = new StubTransport();
        transport.patchSupported = false;
        JdkDiscordConnection connection = connection(transport, "wss://gateway.invalid");

        DiscordResult result = connection.execute(
            new DiscordOperation.EditMessage("42", "99", "editado"));

        DiscordResult.Failed failed = assertInstanceOf(DiscordResult.Failed.class, result);
        assertTrue(failed.reason().contains("PATCH"), failed.reason());
    }

    @Test
    void banMemberPutsTheDeleteSeconds() throws Exception {
        StubTransport transport = new StubTransport();
        JdkDiscordConnection connection = connection(transport, "wss://gateway.invalid");

        DiscordResult result = connection.execute(
            new DiscordOperation.BanMember("7", "9", 3600, "spam"));

        assertInstanceOf(DiscordResult.Ok.class, result);
        assertEquals("PUT", transport.methods.get(0));
        assertTrue(transport.urls.get(0).endsWith("/guilds/7/bans/9"),
            transport.urls.get(0));
        assertTrue(transport.bodies.get(0).contains("\"delete_message_seconds\":3600"),
            transport.bodies.get(0));
    }

    @Test
    void assignRolePutsTheRoleEndpoint() throws Exception {
        StubTransport transport = new StubTransport();
        JdkDiscordConnection connection = connection(transport, "wss://gateway.invalid");

        DiscordResult result = connection.execute(
            new DiscordOperation.AssignRole("7", "9", "11", "promote"));

        assertInstanceOf(DiscordResult.Ok.class, result);
        assertEquals("PUT", transport.methods.get(0));
        assertTrue(transport.urls.get(0).endsWith("/guilds/7/members/9/roles/11"),
            transport.urls.get(0));
    }

    @Test
    void platformErrorsMapToExplicitResults() throws Exception {
        StubTransport transport = new StubTransport();
        JdkDiscordConnection connection = connection(transport, "wss://gateway.invalid");
        DiscordOperation operation = new DiscordOperation.DeleteMessage("42", "99", null);

        transport.failure = new TransportException(404, "Not Found");
        assertInstanceOf(DiscordResult.NotFound.class, connection.execute(operation));

        transport.failure = new TransportException(403, "Forbidden");
        assertInstanceOf(DiscordResult.Forbidden.class, connection.execute(operation));

        transport.failure = new TransportException(409, "Conflict");
        assertInstanceOf(DiscordResult.Conflict.class, connection.execute(operation));

        transport.failure = new TransportException(429, "{\"retry_after\":1.5}");
        DiscordResult.RateLimited limited = assertInstanceOf(
            DiscordResult.RateLimited.class, connection.execute(operation));
        assertEquals(1500, limited.retryAfterMillis());

        transport.failure = new TransportException(500, "Boom");
        assertInstanceOf(DiscordResult.Failed.class, connection.execute(operation));
    }

    @Test
    void nativeOperationAfterCloseFailsWithoutARequest() throws Exception {
        StubTransport transport = new StubTransport();
        JdkDiscordConnection connection = connection(transport, "wss://gateway.invalid");
        connection.close();

        DiscordResult result = connection.execute(
            new DiscordOperation.DeleteMessage("42", "99", null));

        assertInstanceOf(DiscordResult.Failed.class, result);
        assertTrue(transport.urls.isEmpty());
    }
}

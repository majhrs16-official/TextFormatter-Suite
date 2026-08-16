package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordSinkTest {

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
    }

    private static Message message(String text) {
        return Message.builder().sender(Actor.unknown("Steve")).text(text).channel("discord").build();
    }

    @Test
    void sendPostsToChannelWithBotToken() throws Exception {
        StubTransport transport = new StubTransport();
        DiscordSink sink = new DiscordSink("https://discord.com/api/v10", "wss://gateway.invalid",
            "abc", 42, 0, transport);

        sink.send(message("hola discord"));

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
        DiscordSink sink = new DiscordSink("https://discord.com/api/v10", "wss://x", "abc", 42, 0, transport);

        boolean threw = false;
        try {
            sink.send(message("x"));
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "expected send to fail on API error payload");
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

        StubTransport transport = new StubTransport();
        DiscordSink sink = new DiscordSink(
            "https://discord.com/api/v10",
            "ws://127.0.0.1:" + server.port() + "/?v=10&encoding=json",
            "tok", 42,
            DiscordSink.INTENT_GUILD_MESSAGES | DiscordSink.INTENT_MESSAGE_CONTENT,
            transport);
        CopyOnWriteArrayList<Message> received = new CopyOnWriteArrayList<>();
        sink.setListener(new SyncListener() {
            @Override public void onMessage(SyncSink s, Message message) { received.add(message); }
            @Override public void onDisconnect(SyncSink s, String reason) { }
        });

        sink.start();

        long deadline = System.currentTimeMillis() + 5000;
        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(1, received.size(),
            "received=" + received + " serverReceived=" + server.received()
                + " heartbeats=" + server.heartbeats()
                + " serverFailure=" + server.failure());
        assertEquals("hola discord", received.get(0).text());
        assertEquals("discord", received.get(0).channel());
        assertEquals("LUNA", received.get(0).sender().name());

        while (server.heartbeats() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(server.heartbeats() >= 1, "gateway must heartbeat on the Hello interval");

        boolean identified = server.received().stream()
            .anyMatch(body -> body.contains("\"op\":2"));
        assertTrue(identified, "gateway must send Identify after Hello");

        sink.stop();
        server.close();
    }
}
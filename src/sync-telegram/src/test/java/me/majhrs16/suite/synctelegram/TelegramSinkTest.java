package me.majhrs16.suite.synctelegram;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.transport.Transport;
import me.majhrs16.suite.transport.TransportException;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramSinkTest {

    private static final String PROGRAM = "0123456789abcdef".repeat(4);

    /** Stub transport that records requests and returns canned JSON. */
    private static final class StubTransport implements Transport {

        record Request(String method, String url, String body) {}

        final List<Request> requests = new CopyOnWriteArrayList<>();
        String getResponse = "{\"ok\":true,\"result\":[]}";
        String postResponse = "{\"ok\":true,\"result\":true}";
        IOException postFailure;

        @Override
        public String get(String url) {
            requests.add(new Request("GET", url, null));
            return getResponse;
        }

        @Override
        public String post(String url, String jsonBody) throws IOException {
            requests.add(new Request("POST", url, jsonBody));
            if (postFailure != null) {
                throw postFailure;
            }
            return postResponse;
        }

        @Override
        public String post(String url, Map<String, String> headers, String jsonBody)
                throws IOException {
            return post(url, jsonBody);
        }

        @Override
        public String put(String url, Map<String, String> headers, String jsonBody) {
            throw new UnsupportedOperationException("PUT is not used by Telegram");
        }

        @Override
        public String delete(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException("DELETE is not used by Telegram");
        }
    }

    private static SyncCall nativeCall(String action, Map<String, Object> args) {
        return new SyncCall("telegram", action, args,
            new Principal.Program(PROGRAM), "key", "test");
    }

    private static JSONObject body(StubTransport transport, int index) {
        return new JSONObject(transport.requests.get(index).body());
    }

    private static TelegramSink sink(StubTransport transport) {
        return new TelegramSink("secret-token", 12345, transport);
    }

    @Test
    void blankTokenIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
            () -> new TelegramSink("  ", 12345, new StubTransport()));
    }

    @Test
    void surfacePublishesTheLcmAndTheNativeTelegramActions() {        SyncSurface surface = sink(new StubTransport()).surface();

        assertEquals("telegram", surface.backend());
        assertTrue(surface.hasLeastCommonMultiple());
        for (String action : List.of(
                "chat.title.set", "chat.leave", "message.delete", "message.edit",
                "chat.member.ban", "chat.member.unban")) {
            assertTrue(surface.action(action).isPresent(), action);
        }
        assertEquals(ActionKind.DESTRUCTIVE,
            surface.action("message.delete").orElseThrow().kind());
        assertEquals(ActionKind.MODERATION,
            surface.action("chat.member.ban").orElseThrow().kind());
        assertEquals(ActionKind.WRITE,
            surface.action("message.edit").orElseThrow().kind());
    }

    @Test
    void sendPostsSendMessageWithChatIdAndText() throws Exception {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);

        sink.send(Message.builder().sender(Actor.unknown("Steve"))
            .text("hola mundo").channel("telegram").build());

        assertEquals(1, transport.requests.size());
        StubTransport.Request request = transport.requests.get(0);
        assertEquals("POST", request.method());
        assertEquals("https://api.telegram.org/botsecret-token/sendMessage", request.url());
        JSONObject json = new JSONObject(request.body());
        assertEquals(12345L, json.getLong("chat_id"));
        assertEquals("hola mundo", json.getString("text"));
    }

    @Test
    void sendThrowsWhenBotApiReportsFailure() {
        StubTransport transport = new StubTransport();
        transport.postResponse = "{\"ok\":false,\"description\":\"chat not found\"}";
        TelegramSink sink = sink(transport);

        assertThrows(IllegalStateException.class, () -> sink.send(
            Message.builder().sender(Actor.unknown("a")).text("x").build()));
    }

    @Test
    void pollDeliversInboundTextMessagesWithOffsetForNextPoll() throws Exception {
        StubTransport transport = new StubTransport();
        transport.getResponse = """
            {"ok":true,"result":[
              {"update_id":10,"message":{"message_id":1,"from":{"id":7,"first_name":"ALEX"},
                "chat":{"id":12345},"text":"hola red"}},
              {"update_id":11,"message":{"message_id":2,"from":{"id":8,"first_name":"NORA"},
                "chat":{"id":99},"text":"otro chat"}}
            ]}""";
        TelegramSink sink = sink(transport);
        CopyOnWriteArrayList<Message> received = new CopyOnWriteArrayList<>();
        sink.setListener(new SyncListener() {
            @Override public void onMessage(SyncSink s, Message message) { received.add(message); }
            @Override public void onDisconnect(SyncSink s, String reason) { }
        });

        int delivered = sink.poll();
        assertEquals(1, delivered);
        assertEquals(1, received.size());
        assertEquals("hola red", received.get(0).text());
        assertEquals("ALEX", received.get(0).sender().name());
        assertEquals("telegram", received.get(0).channel());

        transport.getResponse = "{\"ok\":true,\"result\":[]}";
        sink.poll();
        assertEquals(2, transport.requests.size());
        boolean usedOffset = transport.requests.get(1).url().contains("offset=12");
        assertTrue(usedOffset, "next poll must advance the update watermark");
    }

    @Test
    void pollSkipsMessagesWithoutText() throws Exception {
        StubTransport transport = new StubTransport();
        transport.getResponse = """
            {"ok":true,"result":[
              {"update_id":20,"message":{"message_id":3,"from":{"id":7,"first_name":"ALEX"},
                "chat":{"id":12345},"text":""}},
              {"update_id":21,"message":{"update_id":21,"channel_post":{"chat":{"id":12345}}}}
            ]}""";
        TelegramSink sink = sink(transport);
        CopyOnWriteArrayList<Message> received = new CopyOnWriteArrayList<>();
        sink.setListener(new SyncListener() {
            @Override public void onMessage(SyncSink s, Message message) { received.add(message); }
            @Override public void onDisconnect(SyncSink s, String reason) { }
        });

        int delivered = sink.poll();
        assertEquals(0, delivered);
        assertTrue(received.isEmpty());
    }

    @Test
    void rejectedPollFailsExplicitlyInsteadOfReportingZeroMessages() {
        StubTransport transport = new StubTransport();
        transport.getResponse = "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}";
        TelegramSink sink = sink(transport);

        assertThrows(IllegalStateException.class, sink::poll);
    }

    @Test
    void officialPrincipalIsDeniedAndNeverCallsTheApi() {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);
        SyncCall call = new SyncCall("telegram", "message.delete",
            Map.of("chat", "-1001", "message", 7),
            new Principal.Official("suite.pipeline"), "", "test");

        SyncResult result = sink.invoke(call);

        assertInstanceOf(SyncResult.Denied.class, result);
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void unknownActionIsUnsupportedAndNeverCallsTheApi() {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);

        SyncResult result = sink.invoke(nativeCall("chat.explode", Map.of()));

        assertInstanceOf(SyncResult.Unsupported.class, result);
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void messageDeletePostsDeleteMessage() {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);

        SyncResult result = sink.invoke(nativeCall("message.delete",
            Map.of("chat", "-1001", "message", 7)));

        assertInstanceOf(SyncResult.Ok.class, result);
        assertEquals(1, transport.requests.size());
        assertEquals("https://api.telegram.org/botsecret-token/deleteMessage",
            transport.requests.get(0).url());
        JSONObject json = body(transport, 0);
        assertEquals("-1001", json.getString("chat_id"));
        assertEquals(7L, json.getLong("message_id"));
    }

    @Test
    void messageEditPostsEditMessageText() {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);

        SyncResult result = sink.invoke(nativeCall("message.edit",
            Map.of("chat", "@canal", "message", 8, "text", "nuevo texto")));

        assertInstanceOf(SyncResult.Ok.class, result);
        assertEquals("https://api.telegram.org/botsecret-token/editMessageText",
            transport.requests.get(0).url());
        JSONObject json = body(transport, 0);
        assertEquals("@canal", json.getString("chat_id"));
        assertEquals(8L, json.getLong("message_id"));
        assertEquals("nuevo texto", json.getString("text"));
    }

    @Test
    void memberBanAndUnbanPostModerationMethods() {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);

        assertInstanceOf(SyncResult.Ok.class, sink.invoke(nativeCall("chat.member.ban",
            Map.of("chat", "-1001", "user", "42", "revokeMessages", true))));
        assertInstanceOf(SyncResult.Ok.class, sink.invoke(nativeCall("chat.member.unban",
            Map.of("chat", "-1001", "user", 42, "onlyIfBanned", false))));

        assertEquals("https://api.telegram.org/botsecret-token/banChatMember",
            transport.requests.get(0).url());
        JSONObject ban = body(transport, 0);
        assertEquals(42L, ban.getLong("user_id"));
        assertTrue(ban.getBoolean("revoke_messages"));

        assertEquals("https://api.telegram.org/botsecret-token/unbanChatMember",
            transport.requests.get(1).url());
        JSONObject unban = body(transport, 1);
        assertEquals(42L, unban.getLong("user_id"));
        assertFalse(unban.getBoolean("only_if_banned"));
    }

    @Test
    void chatTitleAndLeavePostTheirMethods() {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);

        assertInstanceOf(SyncResult.Ok.class, sink.invoke(nativeCall("chat.title.set",
            Map.of("chat", "-1001", "title", "nuevo titulo"))));
        assertInstanceOf(SyncResult.Ok.class, sink.invoke(nativeCall("chat.leave",
            Map.of("chat", "-1001"))));

        assertEquals("https://api.telegram.org/botsecret-token/setChatTitle",
            transport.requests.get(0).url());
        assertEquals("nuevo titulo", body(transport, 0).getString("title"));
        assertEquals("https://api.telegram.org/botsecret-token/leaveChat",
            transport.requests.get(1).url());
        assertEquals("-1001", body(transport, 1).getString("chat_id"));
    }

    @Test
    void invalidNativeArgumentsAreReportedWithoutCallingTheApi() {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);

        assertInstanceOf(SyncResult.InvalidArgument.class,
            sink.invoke(nativeCall("message.delete", Map.of("chat", " ", "message", 7))));
        assertInstanceOf(SyncResult.InvalidArgument.class,
            sink.invoke(nativeCall("message.delete", Map.of("chat", "-1", "message", 0))));
        assertInstanceOf(SyncResult.InvalidArgument.class,
            sink.invoke(nativeCall("chat.member.ban", Map.of("chat", "-1", "user", "abc"))));
        assertInstanceOf(SyncResult.InvalidArgument.class,
            sink.invoke(nativeCall("chat.member.ban", Map.of("chat", "-1", "user", "42",
                "revokeMessages", "yes"))));
        assertInstanceOf(SyncResult.InvalidArgument.class,
            sink.invoke(nativeCall("message.edit",
                Map.of("chat", "-1", "message", 7, "text", ""))));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void botApiErrorsMapToExplicitSyncResults() {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);
        SyncCall call = nativeCall("message.delete", Map.of("chat", "-1", "message", 7));

        transport.postFailure = new TransportException(400,
            "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message to delete not found\"}");
        assertInstanceOf(SyncResult.NotFound.class, sink.invoke(call));

        transport.postFailure = new TransportException(400,
            "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat_id is empty\"}");
        assertInstanceOf(SyncResult.InvalidArgument.class, sink.invoke(call));

        transport.postFailure = new TransportException(401,
            "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        assertInstanceOf(SyncResult.HostFailure.class, sink.invoke(call));

        transport.postFailure = new TransportException(403,
            "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot is not a member\"}");
        assertInstanceOf(SyncResult.HostFailure.class, sink.invoke(call));

        transport.postFailure = new TransportException(404,
            "{\"ok\":false,\"error_code\":404,\"description\":\"Not Found\"}");
        assertInstanceOf(SyncResult.NotFound.class, sink.invoke(call));

        transport.postFailure = new TransportException(409,
            "{\"ok\":false,\"error_code\":409,\"description\":\"Conflict\"}");
        assertInstanceOf(SyncResult.Conflict.class, sink.invoke(call));

        transport.postFailure = new TransportException(429,
            "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\","
                + "\"parameters\":{\"retry_after\":1.5}}");
        SyncResult.RateLimited limited = assertInstanceOf(SyncResult.RateLimited.class,
            sink.invoke(call));
        assertEquals(1_500, limited.retryAfterMillis());

        transport.postFailure = new TransportException(500, "upstream exploded");
        assertInstanceOf(SyncResult.HostFailure.class, sink.invoke(call));
    }

    @Test
    void networkFailureMapsToHostFailure() {
        StubTransport transport = new StubTransport();
        transport.postFailure = new IOException("connection reset");
        TelegramSink sink = sink(transport);

        SyncResult result = sink.invoke(nativeCall("chat.leave", Map.of("chat", "-1")));

        assertInstanceOf(SyncResult.HostFailure.class, result);
    }

    @Test
    void closedSinkFailsExplicitlyWithoutReachingTheNetwork() throws Exception {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);
        sink.close();
        sink.close();

        assertInstanceOf(SyncResult.HostFailure.class, sink.invoke(
            nativeCall("chat.leave", Map.of("chat", "-1"))));
        assertThrows(IOException.class, () -> sink.send(
            Message.builder().sender(Actor.unknown("a")).text("x").build()));
        assertThrows(IOException.class, sink::poll);
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void stopKeepsTheTokenSoAReloadedBusCanRestartTheSink() {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);

        sink.stop();
        sink.start();

        assertInstanceOf(SyncResult.Ok.class, sink.invoke(
            nativeCall("chat.leave", Map.of("chat", "-1"))));
        assertEquals(1, transport.requests.size());
    }

    @Test
    void lcmStillWorksThroughInvoke() throws Exception {
        StubTransport transport = new StubTransport();
        TelegramSink sink = sink(transport);
        Message outbound = Message.builder().text("hola").channel("telegram").build();
        SyncCall call = new SyncCall("telegram", "message.send",
            Map.of("message", outbound),
            new Principal.Official("suite.pipeline"), "key", "test");

        SyncResult result = sink.invoke(call);

        assertInstanceOf(SyncResult.Ok.class, result);
        assertEquals(1, transport.requests.size());
        assertEquals("hola", body(transport, 0).getString("text"));
    }
}

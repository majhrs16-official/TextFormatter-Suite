package me.majhrs16.suite.synctelegram;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.transport.Transport;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramSinkTest {

    /** Stub transport that records requests and returns canned JSON. */
    private static final class StubTransport implements Transport {
        final java.util.List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        String getResponse = "{\"ok\":true,\"result\":[]}";
        String postResponse = "{\"ok\":true}";

        @Override
        public String get(String url) {
            requests.add(url);
            return getResponse;
        }

        @Override
        public String post(String url, String jsonBody) {
            requests.add(url);
            return postResponse;
        }

        @Override
        public String post(String url, java.util.Map<String, String> headers, String jsonBody) {
            return post(url, jsonBody);
        }
    }

    @Test
    void sendPostsSendMessageWithChatIdAndText() throws Exception {
        StubTransport transport = new StubTransport();
        TelegramSink sink = new TelegramSink("secret-token", 12345, transport);

        sink.send(Message.builder().sender(Actor.unknown("Steve"))
            .text("hola mundo").channel("telegram").build());

        assertEquals(1, transport.requests.size());
        String url = transport.requests.get(0);
        assertTrue(url.startsWith("https://api.telegram.org/botsecret-token/sendMessage?"));
        assertTrue(url.contains("chat_id=12345"), url);
        assertTrue(url.contains("text=hola+mundo"), url);
    }

    @Test
    void sendThrowsWhenBotApiReportsFailure() {
        StubTransport transport = new StubTransport();
        transport.getResponse = "{\"ok\":false,\"description\":\"chat not found\"}";
        TelegramSink sink = new TelegramSink("token", 1, transport);

        boolean threw = false;
        try {
            sink.send(Message.builder().sender(Actor.unknown("a")).text("x").build());
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "expected send to fail when ok=false");
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
        TelegramSink sink = new TelegramSink("token", 12345, transport);
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
        boolean usedOffset = transport.requests.get(1).contains("offset=12");
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
        TelegramSink sink = new TelegramSink("token", 12345, transport);
        CopyOnWriteArrayList<Message> received = new CopyOnWriteArrayList<>();
        sink.setListener(new SyncListener() {
            @Override public void onMessage(SyncSink s, Message message) { received.add(message); }
            @Override public void onDisconnect(SyncSink s, String reason) { }
        });

        int delivered = sink.poll();
        assertEquals(0, delivered);
        assertTrue(received.isEmpty());
    }
}

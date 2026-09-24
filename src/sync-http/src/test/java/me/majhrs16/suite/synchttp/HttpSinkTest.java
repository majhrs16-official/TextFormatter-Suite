package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSinkTest {

    private static HttpServer stubWebhook(AtomicReference<String> captured) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
            captured.set(body);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void postsOutboundMessageToWebhook() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer webhook = stubWebhook(captured);
        try {
            String url = "http://localhost:" + webhook.getAddress().getPort() + "/webhook";
            HttpSink sink = new HttpSink(url, 0, "/hook");

            sink.send(Message.builder()
                .sender(Actor.unknown("Steve"))
                .direction(Direction.others())
                .text("hola")
                .channel("chat")
                .build());

            assertTrue(captured.get().contains("\"texts\":[\"hola\"]"));
        } finally {
            webhook.stop(0);
        }
    }

    @Test
    void inboundEndpointDeliversToListener() throws Exception {
        CopyOnWriteArrayList<Message> received = new CopyOnWriteArrayList<>();
        HttpSink sink = new HttpSink("http://localhost:1/unused", 0, "/hook");
        sink.setListener(new SyncListener() {
            @Override public void onMessage(me.majhrs16.suite.api.spi.SyncSink s, Message message) {
                received.add(message);
            }
            @Override public void onDisconnect(me.majhrs16.suite.api.spi.SyncSink s, String reason) {
            }
        });
        try {
            sink.start();
            String target = "http://localhost:" + sink.inboundPort() + "/hook";
            HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(target))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    MessageCodec.toJson(Message.builder()
                        .sender(Actor.unknown("DiscordBot"))
                        .direction(Direction.others())
                        .text("desde fuera")
                        .channel("discord")
                        .build()).toString()))
                .build();
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(1, received.size());
            assertEquals("desde fuera", received.get(0).text());
            assertEquals("discord", received.get(0).channel());
        } finally {
            sink.stop();
        }
    }
}
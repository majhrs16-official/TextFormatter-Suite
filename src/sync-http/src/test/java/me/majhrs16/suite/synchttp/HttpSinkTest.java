package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.transport.MessageCodec;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSinkTest {

    private static final Principal PROGRAM =
        new Principal.Program("0123456789abcdef".repeat(4));

    private static HttpServer stubWebhook(AtomicReference<String> captured) throws Exception {
        return stubWebhook(captured, 204, null);
    }

    private static HttpServer stubWebhook(
            AtomicReference<String> captured, int status, String retryAfter) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
            captured.set(body);
            if (retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String webhookUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort() + "/webhook";
    }

    private static Message message(String text) {
        return Message.builder()
            .sender(Actor.unknown("Steve"))
            .direction(Direction.others())
            .text(text)
            .channel("chat")
            .build();
    }

    private static SyncCall nativeCall(
            String action, Map<String, Object> args, Principal principal) {
        return new SyncCall("http", action, args, principal, "", "test");
    }

    @Test
    void postsOutboundMessageToWebhook() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer webhook = stubWebhook(captured);
        HttpSink sink = new HttpSink(
            webhookUrl(webhook), 0, "/hook", "127.0.0.1", null, null);
        try {
            sink.send(message("hola"));

            assertTrue(captured.get().contains("\"texts\":[\"hola\"]"));
        } finally {
            sink.stop();
            webhook.stop(0);
        }
    }

    @Test
    void inboundEndpointDeliversToListener() throws Exception {
        CopyOnWriteArrayList<Message> received = new CopyOnWriteArrayList<>();
        HttpSink sink = new HttpSink("http://localhost:1/unused", 0, "/hook", "127.0.0.1", null, null);
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

    @Test
    void publishesLeastCommonMultipleAndNativeActions() {
        HttpSink sink = new HttpSink(
            "http://localhost:1/unused", 0, "/hook", "127.0.0.1", null, null);
        try {
            SyncSurface surface = sink.surface();

            assertEquals("http", surface.backend());
            assertTrue(surface.hasLeastCommonMultiple());
            assertEquals(ActionKind.WRITE,
                surface.action("webhook.send").orElseThrow().kind());
            assertEquals(ActionKind.READ,
                surface.action("endpoint.info").orElseThrow().kind());
        } finally {
            sink.stop();
        }
    }

    @Test
    void webhookSendPostsNeutralPayload() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer webhook = stubWebhook(captured);
        HttpSink sink = new HttpSink(
            webhookUrl(webhook), 0, "/hook", "127.0.0.1", null, null);
        try {
            SyncResult result = sink.invoke(nativeCall(
                "webhook.send",
                Map.<String, Object>of("payload", Map.of("event", "deploy", "id", 7)),
                PROGRAM));

            assertTrue(result instanceof SyncResult.Ok, result.toString());
            assertTrue(captured.get().contains("\"event\":\"deploy\""));
            assertTrue(captured.get().contains("\"id\":7"));
        } finally {
            sink.stop();
            webhook.stop(0);
        }
    }

    @Test
    void officialPrincipalCannotInvokeNativeActions() {
        HttpSink sink = new HttpSink(
            "http://localhost:1/unused", 0, "/hook", "127.0.0.1", null, null);
        try {
            SyncResult result = sink.invoke(nativeCall(
                "webhook.send",
                Map.<String, Object>of("payload", Map.of()),
                new Principal.Official("sync-bus")));

            assertTrue(result instanceof SyncResult.Denied, result.toString());
        } finally {
            sink.stop();
        }
    }

    @Test
    void unsupportedActionIsExplicit() {
        HttpSink sink = new HttpSink(
            "http://localhost:1/unused", 0, "/hook", "127.0.0.1", null, null);
        try {
            SyncResult result = sink.invoke(nativeCall(
                "channel.create", Map.of(), PROGRAM));

            assertTrue(result instanceof SyncResult.Unsupported, result.toString());
        } finally {
            sink.stop();
        }
    }

    @Test
    void invalidPayloadIsRejectedWithoutNetwork() {
        HttpSink sink = new HttpSink(
            "http://localhost:1/unused", 0, "/hook", "127.0.0.1", null, null);
        try {
            SyncResult missing = sink.invoke(nativeCall("webhook.send", Map.of(), PROGRAM));
            SyncResult wrongType = sink.invoke(nativeCall(
                "webhook.send",
                Map.<String, Object>of("payload", "not-an-object"),
                PROGRAM));

            assertTrue(missing instanceof SyncResult.InvalidArgument, missing.toString());
            assertTrue(wrongType instanceof SyncResult.InvalidArgument, wrongType.toString());
        } finally {
            sink.stop();
        }
    }

    @Test
    void mapsHttpStatusesToExplicitResults() throws Exception {
        Map<Integer, Class<? extends SyncResult>> expected = Map.of(
            400, SyncResult.InvalidArgument.class,
            401, SyncResult.HostFailure.class,
            403, SyncResult.HostFailure.class,
            404, SyncResult.NotFound.class,
            409, SyncResult.Conflict.class,
            500, SyncResult.HostFailure.class,
            503, SyncResult.HostFailure.class);

        for (Map.Entry<Integer, Class<? extends SyncResult>> entry : expected.entrySet()) {
            AtomicReference<String> captured = new AtomicReference<>();
            HttpServer webhook = stubWebhook(captured, entry.getKey(), null);
            HttpSink sink = new HttpSink(
                webhookUrl(webhook), 0, "/hook", "127.0.0.1", null, null);
            try {
                SyncResult result = sink.invoke(nativeCall(
                    "webhook.send",
                    Map.<String, Object>of("payload", Map.of("k", "v")),
                    PROGRAM));

                assertTrue(entry.getValue().isInstance(result),
                    () -> "status " + entry.getKey() + " produced " + result);
            } finally {
                sink.stop();
                webhook.stop(0);
            }
        }
    }

    @Test
    void rateLimitedCarriesRetryAfter() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer webhook = stubWebhook(captured, 429, "2");
        HttpSink sink = new HttpSink(
            webhookUrl(webhook), 0, "/hook", "127.0.0.1", null, null);
        try {
            SyncResult result = sink.invoke(nativeCall(
                "webhook.send",
                Map.<String, Object>of("payload", Map.of("k", "v")),
                PROGRAM));

            assertTrue(result instanceof SyncResult.RateLimited, result.toString());
            assertEquals(2_000, ((SyncResult.RateLimited) result).retryAfterMillis());
        } finally {
            sink.stop();
            webhook.stop(0);
        }
    }

    @Test
    void endpointInfoDoesNotExposeSecrets() {
        HttpSink sink = new HttpSink(
            "http://hooks.example:8080/secret-path?token=abc",
            0, "/hook", "127.0.0.1", "bearer-secret", "hmac-secret");
        try {
            SyncResult result = sink.invoke(nativeCall("endpoint.info", Map.of(), PROGRAM));

            assertTrue(result instanceof SyncResult.Ok, result.toString());
            String info = String.valueOf(((SyncResult.Ok) result).value());
            assertTrue(info.contains("hooks.example"));
            assertFalse(info.contains("bearer-secret"));
            assertFalse(info.contains("hmac-secret"));
            assertFalse(info.contains("secret-path"));
            assertFalse(info.contains("token=abc"));
        } finally {
            sink.stop();
        }
    }

    @Test
    void stoppedSinkRejectsNativeActionsAndSend() {
        HttpSink sink = new HttpSink(
            "http://localhost:1/unused", 0, "/hook", "127.0.0.1", null, null);
        sink.stop();

        SyncResult send = sink.invoke(nativeCall(
            "webhook.send",
            Map.<String, Object>of("payload", Map.of("k", "v")),
            PROGRAM));
        SyncResult info = sink.invoke(nativeCall("endpoint.info", Map.of(), PROGRAM));

        assertTrue(send instanceof SyncResult.HostFailure, send.toString());
        assertTrue(info instanceof SyncResult.HostFailure, info.toString());
        assertThrows(IOException.class, () -> sink.send(message("detenido")));
    }

    @Test
    void restartsAfterStop() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer webhook = stubWebhook(captured);
        HttpSink sink = new HttpSink(
            webhookUrl(webhook), 0, "/hook", "127.0.0.1", null, null);
        try {
            sink.start();
            sink.stop();
            sink.start();

            sink.send(message("reinicio"));

            assertTrue(captured.get().contains("reinicio"));
            assertTrue(sink.inboundPort() > 0);
        } finally {
            sink.stop();
            webhook.stop(0);
        }
    }
}

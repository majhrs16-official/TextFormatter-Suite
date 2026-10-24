package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.transport.MessageCodec;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests de integración para {@link HttpSink}.
 * Validan el envío de mensajes salientes (webhook) y la recepción de mensajes entrantes.
 */
@DisplayName("HttpSink — integración")
class HttpSinkIntegrationTest {

    private static final Actor STEVE = new Actor(
        UUID.fromString("11111111-1111-1111-1111-111111111111"), "Steve",
        Actor.ActorKind.PLAYER, Language.EN, null);

    private WireMockServer wireMock;
    private HttpSink sink;
    private PluginLogger logger;

    @BeforeEach
    void setUp() throws Exception {
        logger = new PluginLogger() {
            @Override public void info(String m, Object... a) {}
            @Override public void warn(String m, Object... a) {}
            @Override public void error(String m, Object... a) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m, Object... a) {}
        };

        // Start WireMock for outbound webhook mocking
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        // Create HttpSink with ephemeral inbound port (0 = auto-assign)
        sink = new HttpSink(
            "http://localhost:" + wireMock.port() + "/webhook",
            0, // ephemeral port
            "/hook",
            "127.0.0.1",
            null, // no auth token
            null  // no HMAC
        );
    }

    @AfterEach
    void tearDown() {
        if (sink != null) {
            try { sink.stop(); } catch (Exception ignored) {}
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    private Message chatMessage(Actor sender, String text) {
        return Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.all())
            .text(text)
            .channel("chat")
            .build();
    }

    @Test
    @DisplayName("start/stop lifecycle funciona correctamente")
    void lifecycleStartStop() throws Exception {
        assertThat(sink.name()).isEqualTo("http");
        assertThat(sink.inboundPort()).isEqualTo(0); // Not started yet

        sink.start();

        int boundPort = sink.inboundPort();
        assertThat(boundPort).isGreaterThan(0);
        
        // Verify server is responding - GET should return 405
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + boundPort + "/hook"))
            .GET()
            .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // Should return 405 (Method Not Allowed) for non-POST
        assertThat(response.statusCode()).isEqualTo(405);

        sink.stop();
        
        // After stop, inboundPort returns configured port (0)
        assertThat(sink.inboundPort()).isEqualTo(0);
    }

    @Test
    @DisplayName("envía mensaje saliente al webhook correctamente")
    void sendsOutboundMessageToWebhook() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(200)));

        sink.start();

        Message msg = chatMessage(STEVE, "hello webhook");
        sink.send(msg);

        // Verify WireMock received the request
        wireMock.verify(postRequestedFor(urlEqualTo("/webhook")));

        sink.stop();
    }

    @Test
    @DisplayName("envía mensaje con contenido JSON correcto")
    void sendsCorrectJsonPayload() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(200)));

        sink.start();

        Message msg = chatMessage(STEVE, "test payload");
        sink.send(msg);

        // Verify request was made
        wireMock.verify(postRequestedFor(urlEqualTo("/webhook")));

        sink.stop();
    }

    @Test
    @DisplayName("lanza excepción en error HTTP 4xx/5xx")
    void throwsOnHttpError() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(500)));

        sink.start();

        Message msg = chatMessage(STEVE, "will fail");
        
        assertThatThrownBy(() -> sink.send(msg))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("webhook HTTP 500");

        sink.stop();
    }

    @Test
    @DisplayName("recibe mensaje entrante y notifica al listener")
    void receivesInboundMessageAndNotifiesListener() throws Exception {
        sink.start();

        AtomicReference<Message> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        sink.setListener(new SyncListener() {
            @Override
            public void onMessage(SyncSink s, Message message) {
                received.set(message);
                latch.countDown();
            }
            @Override
            public void onDisconnect(SyncSink s, String reason) {}
        });

        int port = sink.inboundPort();
        String json = MessageCodec.toJson(chatMessage(STEVE, "inbound test")).toString();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/hook"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        Message receivedMsg = received.get();
        assertThat(receivedMsg).isNotNull();
        assertThat(receivedMsg.text()).isEqualTo("inbound test");
        assertThat(receivedMsg.sender().name()).isEqualTo("Steve");

        sink.stop();
    }

    @Test
    @DisplayName("rechaza requests no POST")
    void rejectsNonPostRequests() throws Exception {
        sink.start();

        int port = sink.inboundPort();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/hook"))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);

        sink.stop();
    }

    @Test
    @DisplayName("rechaza requests sin Content-Type application/json")
    void rejectsInvalidContentType() throws Exception {
        sink.start();

        int port = sink.inboundPort();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/hook"))
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString("plain text"))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(415);

        sink.stop();
    }

    @Test
    @DisplayName("rechaza payloads mayores a 1MB")
    void rejectsLargePayload() throws Exception {
        sink.start();

        int port = sink.inboundPort();
        String largeBody = "x".repeat(2 * 1024 * 1024); // 2MB

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/hook"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(largeBody))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);

        sink.stop();
    }

    @Test
    @DisplayName("autenticación Bearer token funciona")
    void bearerTokenAuthentication() throws Exception {
        // Create sink with auth token
        HttpSink authSink = new HttpSink(
            "http://localhost:" + wireMock.port() + "/webhook",
            0, "/hook", "127.0.0.1",
            "secret-token", // auth token
            null
        );

        wireMock.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(200)));

        authSink.start();

        int port = authSink.inboundPort();
        String json = MessageCodec.toJson(chatMessage(STEVE, "authed")).toString();
        String nonce = "bearer-nonce-123";
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        HttpClient client = HttpClient.newHttpClient();

        // Request with correct token and nonce/timestamp
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/hook"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer secret-token")
            .header("X-Nonce", nonce)
            .header("X-Timestamp", timestamp)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        // Request without token should fail
        request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/hook"))
            .header("Content-Type", "application/json")
            .header("X-Nonce", "another-nonce")
            .header("X-Timestamp", timestamp)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);

        authSink.stop();
    }

    @Test
    @DisplayName("protección contra replay con nonce/timestamp")
    void replayProtection() throws Exception {
        sink.start();

        int port = sink.inboundPort();
        String json = MessageCodec.toJson(chatMessage(STEVE, "replay test")).toString();
        String nonce = "test-nonce-123";
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());

        HttpClient client = HttpClient.newHttpClient();
        
        // First request with nonce/timestamp - should succeed
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/hook"))
            .header("Content-Type", "application/json")
            .header("X-Nonce", nonce)
            .header("X-Timestamp", timestamp)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        // Second request with same nonce/timestamp - should fail (replay)
        request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/hook"))
            .header("Content-Type", "application/json")
            .header("X-Nonce", nonce)
            .header("X-Timestamp", timestamp)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(409); // Conflict

        sink.stop();
    }

    @Test
    @DisplayName("HMAC authentication funciona")
    void hmacAuthentication() throws Exception {
        String hmacSecret = "test-hmac-secret";
        
        HttpSink hmacSink = new HttpSink(
            "http://localhost:" + wireMock.port() + "/webhook",
            0, "/hook", "127.0.0.1",
            null, // no bearer token
            hmacSecret
        );

        wireMock.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(200)));

        hmacSink.start();

        int port = hmacSink.inboundPort();
        String json = MessageCodec.toJson(chatMessage(STEVE, "hmac test")).toString();
        String nonce = "hmac-nonce-456";
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        
        // Compute HMAC: nonce + timestamp + body
        String data = nonce + timestamp + json;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        String signature = hex.toString();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/hook"))
            .header("Content-Type", "application/json")
            .header("X-Nonce", nonce)
            .header("X-Timestamp", timestamp)
            .header("X-Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        // Invalid signature should fail
        request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/hook"))
            .header("Content-Type", "application/json")
            .header("X-Nonce", "different-nonce")
            .header("X-Timestamp", timestamp)
            .header("X-Signature", "invalidsignature")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);

        hmacSink.stop();
    }

    @Test
    @DisplayName("maneja múltiples mensajes concurrentes")
    void handlesConcurrentMessages() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(200)));

        sink.start();

        // Send multiple messages concurrently
        int count = 20;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(10);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < count; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    sink.send(chatMessage(STEVE, "concurrent " + idx));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        // Wait for all to complete
        for (var f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Verify all were sent
        wireMock.verify(count, postRequestedFor(urlEqualTo("/webhook")));

        sink.stop();
    }
}
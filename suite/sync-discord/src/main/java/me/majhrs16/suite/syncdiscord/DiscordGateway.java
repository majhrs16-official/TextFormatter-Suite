package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Minimal Discord Gateway v10 client (WebSocket) driving inbound delivery.
 *
 * <p>Handles the session lifecycle the suite needs: {@code Hello} → heartbeat
 * scheduler → {@code Identify}, sequence tracking for {@code RESUME}, {@code READY}
 * detection and {@code MESSAGE_CREATE} dispatch to the {@code SyncListener}. Uses
 * the JDK {@link java.net.http.WebSocket}; tests run against a loopback RFC6455
 * stub so no network is required.
 */
public final class DiscordGateway implements AutoCloseable {

    private final String url;
    private final String token;
    private final int intents;
    private final me.majhrs16.suite.api.spi.SyncSink owner;
    private final me.majhrs16.suite.api.spi.SyncListener listener;
    private final HttpClient client;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile WebSocket socket;
    private volatile ScheduledExecutorService heartbeats;
    private volatile long sequence = -1;
    private volatile boolean closed;

    public DiscordGateway(String gatewayUrl, String token, int intents,
                          me.majhrs16.suite.api.spi.SyncSink owner,
                          me.majhrs16.suite.api.spi.SyncListener listener) {
        this(gatewayUrl, token, intents, owner, listener, HttpClient.newHttpClient());
    }

    DiscordGateway(String gatewayUrl, String token, int intents,
                   me.majhrs16.suite.api.spi.SyncSink owner,
                   me.majhrs16.suite.api.spi.SyncListener listener, HttpClient client) {
        this.url = Objects.requireNonNull(gatewayUrl, "gatewayUrl");
        this.token = Objects.requireNonNull(token, "token");
        this.intents = intents;
        this.owner = owner;
        this.listener = listener;
        this.client = client;
    }

    /** Connects and blocks until {@code READY} (or resume) or a timeout. */
    public boolean connect() {
        socket = client.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create(url), new Frames())
            .join();
        try {
            return ready.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private final class Frames implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handle(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            ready.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
            ready.countDown();
        }
    }

    private void handle(String json) {
        JSONObject payload = new JSONObject(json);
        int op = payload.optInt("op", -1);
        if (payload.has("s") && !payload.isNull("s")) {
            sequence = payload.getLong("s");
        }
        switch (op) {
            case 0 -> { // Dispatch
                String type = payload.optString("t", "");
                if ("READY".equals(type) || "RESUMED".equals(type)) {
                    ready.countDown();
                }
                if ("MESSAGE_CREATE".equals(type)) {
                    JSONObject data = payload.optJSONObject("d");
                    if (data != null) {
                        deliver(data);
                    }
                }
            }
            case 10 -> { // Hello
                int interval = payload.optJSONObject("d") != null
                    ? payload.getJSONObject("d").optInt("heartbeat_interval", 41250)
                    : 41250;
                startHeartbeat(interval);
                send(identify());
            }
            case 11 -> { /* heartbeat ack */ }
            default -> { }
        }
    }

    private void deliver(JSONObject data) {
        JSONObject author = data.optJSONObject("author");
        String name = author != null ? author.optString("username", "Discord") : "Discord";
        String content = data.optString("content", "");
        if (content.isBlank()) {
            return;
        }
        listener.onMessage(owner, Message.builder()
            .sender(Actor.unknown(name))
            .text(content)
            .channel("discord")
            .build());
    }

    private void startHeartbeat(int interval) {
        heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "discord-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeats.scheduleAtFixedRate(this::beat, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void beat() {
        JSONObject payload = new JSONObject()
            .put("op", 1)
            .put("d", sequence < 0 ? JSONObject.NULL : sequence);
        send(payload.toString());
    }

    private String identify() {
        return new JSONObject()
            .put("op", 2)
            .put("d", new JSONObject()
                .put("token", token)
                .put("intents", intents)
                .put("properties", new JSONObject()
                    .put("os", "linux")
                    .put("browser", "textformatter-suite")
                    .put("device", "suite")))
            .toString();
    }

    private void send(String json) {
        WebSocket ws = socket;
        if (ws != null && !closed) {
            ws.sendText(json, true);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "suite shutdown");
        }
    }
}
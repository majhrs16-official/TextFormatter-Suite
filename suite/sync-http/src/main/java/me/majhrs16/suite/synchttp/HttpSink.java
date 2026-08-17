package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * HTTP edge connector: pushes outbound messages to a webhook URL and exposes
 * a local {@code POST} endpoint that feeds inbound messages to the engine
 * through {@link SyncListener}.
 */
public final class HttpSink implements SyncSink {

    private final String outboundUrl;
    private final int inboundPort;
    private final String inboundPath;
    private final HttpClient client;
    private volatile SyncListener listener;
    private volatile HttpServer server;

    public HttpSink(String outboundUrl, int inboundPort, String inboundPath) {
        this.outboundUrl = Objects.requireNonNull(outboundUrl, "outboundUrl");
        this.inboundPort = inboundPort;
        this.inboundPath = inboundPath == null ? "/hook" : inboundPath;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Override
    public String name() {
        return "http";
    }

    /** @return the actual bound inbound port once started (0 = ephemeral). */
    public int inboundPort() {
        return server != null
            ? server.getAddress().getPort()
            : inboundPort;
    }

    @Override
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(inboundPort), 0);
        created.createContext(inboundPath, this::handleInbound);
        created.start();
        server = created;
    }

    @Override
    public synchronized void stop() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            current.stop(0);
        }
    }

    @Override
    public void send(Message message) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(outboundUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                MessageCodec.toJson(message).toString()))
            .build();
        HttpResponse<String> response = client.send(request,
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("webhook HTTP " + response.statusCode());
        }
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    private void handleInbound(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
            SyncListener current = listener;
            if (current != null) {
                current.onMessage(this, MessageCodec.fromJson(body));
            }
            exchange.sendResponseHeaders(200, -1);
        } finally {
            exchange.close();
        }
    }
}
package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * HTTP edge connector: pushes outbound messages to a webhook URL and exposes
 * a local {@code POST} endpoint that feeds inbound messages to the engine
 * through {@link SyncListener}.
 * <p>
 * Security: Limits inbound body size to prevent DoS via large payloads.
 * </p>
 */
public final class HttpSink implements SyncSink {

    private static final int MAX_BODY_BYTES = 1024 * 1024; // 1MB limit

    private final String outboundUrl;
    private final int inboundPort;
    private final String inboundPath;
    private final HttpClient client;
    private volatile SyncListener listener;
    private volatile HttpServer server;
    private ThreadPoolExecutor executor;

    public HttpSink(String outboundUrl, int inboundPort, String inboundPath) {
        this.outboundUrl = Objects.requireNonNull(outboundUrl, "outboundUrl");
        this.inboundPort = inboundPort;
        this.inboundPath = inboundPath == null ? "/hook" : inboundPath;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        // Bounded executor for HTTP server (prevents thread exhaustion)
        this.executor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "http-sink-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
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
        // Use bounded executor to prevent thread exhaustion (DOS-1)
        created.setExecutor(executor);
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
        // Shutdown executor gracefully
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void send(Message message) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(outboundUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                me.majhrs16.suite.transport.MessageCodec.toJson(message).toString()))
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
            
            // Check Content-Length header if present
            String contentLengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
            if (contentLengthHeader != null) {
                try {
                    long contentLength = Long.parseLong(contentLengthHeader);
                    if (contentLength > MAX_BODY_BYTES) {
                        exchange.sendResponseHeaders(413, -1); // Payload Too Large
                        return;
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid Content-Length, will be caught by streaming limit
                }
            }
            
            // Read body with size limit
            String body = readLimitedBody(exchange.getRequestBody(), MAX_BODY_BYTES);
            
            SyncListener current = listener;
            if (current != null) {
                current.onMessage(this, me.majhrs16.suite.transport.MessageCodec.fromJson(body));
            }
            exchange.sendResponseHeaders(200, -1);
        } finally {
            exchange.close();
        }
    }
    
    private String readLimitedBody(InputStream inputStream, int maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int totalRead = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > maxBytes) {
                throw new IOException("Body size exceeds maximum allowed: " + maxBytes + " bytes");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}

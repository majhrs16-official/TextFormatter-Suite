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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * HTTP edge connector: pushes outbound messages to a webhook URL and exposes
 * a local {@code POST} endpoint that feeds inbound messages to the engine
 * through {@link SyncListener}.
 * <p>
 * Security: Limits inbound body size to prevent DoS via large payloads.
 * Supports Bearer token and HMAC-SHA256 authentication.
 * </p>
 */
public final class HttpSink implements SyncSink {

    @Override
    public DeliverySemantics deliverySemantics() {
        return DeliverySemantics.AT_LEAST_ONCE;
    }

    @Override
    public Ordering ordering() {
        return Ordering.PER_CHANNEL;
    }

    private static final int MAX_BODY_BYTES = 1024 * 1024; // 1MB limit
    private static final int MAX_REPLAY_WINDOW_SECONDS = 300; // 5 minutes for replay protection

    private final String outboundUrl;
    private final int inboundPort;
    private final String inboundPath;
    private final String bindAddress;
    private final String authToken;
    private final String hmacSecret;
    private final HttpClient client;
    private volatile SyncListener listener;
    private volatile HttpServer server;
    private ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, Long> recentNonces = new ConcurrentHashMap<>();

    public HttpSink(String outboundUrl, int inboundPort, String inboundPath, String bindAddress, String authToken, String hmacSecret) {
        this.outboundUrl = Objects.requireNonNull(outboundUrl, "outboundUrl");
        this.inboundPort = inboundPort;
        this.inboundPath = inboundPath == null ? "/hook" : inboundPath;
        this.bindAddress = bindAddress == null ? "127.0.0.1" : bindAddress;
        this.authToken = authToken;
        this.hmacSecret = hmacSecret;
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

    /** @deprecated Use constructor with bindAddress, authToken, and hmacSecret. */
    @Deprecated
    public HttpSink(String outboundUrl, int inboundPort, String inboundPath) {
        this(outboundUrl, inboundPort, inboundPath, "127.0.0.1", null, null);
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
        HttpServer created = HttpServer.create(new InetSocketAddress(bindAddress, inboundPort), 0);
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
            
            // Require Content-Type: application/json
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("application/json")) {
                exchange.sendResponseHeaders(415, -1); // Unsupported Media Type
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
            
            // Read body with size limit (once, for both HMAC verification and JSON parsing)
            String body = readLimitedBody(exchange.getRequestBody(), MAX_BODY_BYTES);
            
            // Authentication check (now with body for HMAC)
            if (!authenticate(exchange, body)) {
                exchange.sendResponseHeaders(401, -1); // Unauthorized
                return;
            }
            
            // Replay protection: check nonce/timestamp
            if (!checkReplayProtection(exchange, body)) {
                exchange.sendResponseHeaders(409, -1); // Conflict (replay)
                return;
            }
            
            SyncListener current = listener;
            if (current != null) {
                current.onMessage(this, me.majhrs16.suite.transport.MessageCodec.fromJson(body));
            }
            exchange.sendResponseHeaders(200, -1);
        } finally {
            exchange.close();
        }
    }
    
    private boolean authenticate(HttpExchange exchange, String body) {
        // If no auth configured, allow only localhost
        if (authToken == null && hmacSecret == null) {
            String remoteAddr = exchange.getRemoteAddress().getAddress().getHostAddress();
            return "127.0.0.1".equals(remoteAddr) || "::1".equals(remoteAddr);
        }
        
        // Check Bearer token
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (authToken != null && authToken.equals(token)) {
                return true;
            }
        }
        
        // Check HMAC signature
        if (hmacSecret != null) {
            String signature = exchange.getRequestHeaders().getFirst("X-Signature");
            String timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");
            String nonce = exchange.getRequestHeaders().getFirst("X-Nonce");
            
            if (signature != null && timestamp != null && nonce != null) {
                try {
                    long ts = Long.parseLong(timestamp);
                    long now = Instant.now().getEpochSecond();
                    if (Math.abs(now - ts) > MAX_REPLAY_WINDOW_SECONDS) {
                        return false; // Timestamp too old/future
                    }
                    
                    // HMAC covers: nonce + timestamp + body (FIX: body included for integrity)
                    String expectedSig = computeHmac(hmacSecret, nonce + timestamp + body);
                    if (signature.equals(expectedSig)) {
                        return true;
                    }
                } catch (Exception ignored) {
                    return false;
                }
            }
            return false;
        }
        // Explicit return for compiler
        return false;
    }

    private String computeHmac(String secret, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC", e);
        }
    }
    
    private boolean checkReplayProtection(HttpExchange exchange, String body) {
        String nonce = exchange.getRequestHeaders().getFirst("X-Nonce");
        String timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");
        
        if (nonce != null && timestamp != null) {
            try {
                long ts = Long.parseLong(timestamp);
                long now = Instant.now().getEpochSecond();
                if (Math.abs(now - ts) > MAX_REPLAY_WINDOW_SECONDS) {
                    return false;
                }
                
                // Check if nonce was recently used
                String key = nonce + ":" + timestamp;
                Long previous = recentNonces.putIfAbsent(key, Instant.now().toEpochMilli());
                if (previous != null) {
                    return false; // Nonce already used
                }
                
                // Clean old nonces periodically
                if (recentNonces.size() > 10000) {
                    long cutoff = Instant.now().toEpochMilli() - (MAX_REPLAY_WINDOW_SECONDS * 1000L);
                    recentNonces.entrySet().removeIf(e -> e.getValue() < cutoff);
                }
                
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        
        // If no nonce/timestamp provided:
        // - If auth is configured (token or HMAC), require nonce/timestamp for replay protection
        // - If no auth configured (localhost only), allow without nonce/timestamp
        return authToken == null && hmacSecret == null;
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

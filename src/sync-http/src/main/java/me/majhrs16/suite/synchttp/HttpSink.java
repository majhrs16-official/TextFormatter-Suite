package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncActionSpec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncParamSpec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.api.sync.SyncValueType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    private static final String WEBHOOK_SEND = "webhook.send";
    private static final String ENDPOINT_INFO = "endpoint.info";
    private static final Set<String> NATIVE_ACTIONS = Set.of(WEBHOOK_SEND, ENDPOINT_INFO);
    private static final SyncSurface SURFACE = buildSurface();

    private final URI outboundUri;
    private final int inboundPort;
    private final String inboundPath;
    private final String bindAddress;
    private final String authToken;
    private final String hmacSecret;
    private volatile SyncListener listener;
    private volatile HttpServer server;
    private volatile HttpClient client;
    private volatile ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, Long> recentNonces = new ConcurrentHashMap<>();

    public HttpSink(String outboundUrl, int inboundPort, String inboundPath, String bindAddress, String authToken, String hmacSecret) {
        this.outboundUri = URI.create(Objects.requireNonNull(outboundUrl, "outboundUrl"));
        this.inboundPort = inboundPort;
        this.inboundPath = inboundPath == null ? "/hook" : inboundPath;
        this.bindAddress = bindAddress == null ? "127.0.0.1" : bindAddress;
        this.authToken = authToken;
        this.hmacSecret = hmacSecret;
        this.client = newClient();
        this.executor = newExecutor();
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    // Bounded executor for HTTP server (prevents thread exhaustion)
    private static ThreadPoolExecutor newExecutor() {
        return new ThreadPoolExecutor(
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

    @Override
    public SyncSurface surface() {
        return SURFACE;
    }

    /**
     * Executes the least common multiple through the default implementation and
     * the HTTP-native actions through this sink.
     *
     * <p>Native actions require a {@link Principal.Program};
     * {@link Principal.Official} only reaches the LCM. A stopped sink rejects
     * both native actions instead of touching the network.</p>
     */
    @Override
    public SyncResult invoke(SyncCall call) {
        Objects.requireNonNull(call, "call");
        if (!name().equals(call.backend()) || !NATIVE_ACTIONS.contains(call.action())) {
            return SyncSink.super.invoke(call);
        }
        if (call.principal() instanceof Principal.Official) {
            return new SyncResult.Denied(
                "Principal.Official may only invoke the least common multiple");
        }
        if (client == null) {
            return new SyncResult.HostFailure("http sink is stopped");
        }
        if (ENDPOINT_INFO.equals(call.action())) {
            return new SyncResult.Ok(endpointInfo());
        }
        try {
            return map(execute(payloadOf(call)));
        } catch (IllegalArgumentException invalid) {
            return new SyncResult.InvalidArgument(invalid.getMessage());
        } catch (JSONException invalidJson) {
            return new SyncResult.InvalidArgument(describe(invalidJson));
        } catch (HttpTimeoutException timeout) {
            return new SyncResult.Timeout(describe(timeout));
        } catch (IOException failure) {
            return new SyncResult.HostFailure(describe(failure));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new SyncResult.HostFailure("interrupted while calling the webhook");
        }
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
        // stop() releases both resources so module deactivation is clean; a
        // bus restart must recreate them instead of reusing a closed client.
        if (client == null) {
            client = newClient();
        }
        if (executor == null || executor.isShutdown()) {
            executor = newExecutor();
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
        ThreadPoolExecutor currentExecutor = executor;
        executor = null;
        if (currentExecutor != null) {
            currentExecutor.shutdown();
            try {
                if (!currentExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    currentExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                currentExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // HttpClient owns a selector thread; close it so module deactivation is
        // clean and the classloader can be released.
        HttpClient currentClient = client;
        client = null;
        if (currentClient != null) {
            currentClient.close();
        }
    }

    @Override
    public void send(Message message) throws IOException, InterruptedException {
        String body = me.majhrs16.suite.transport.MessageCodec.toJson(message).toString();
        HttpResponse<String> response = execute(body);
        if (response.statusCode() >= 400) {
            throw new IOException("webhook HTTP " + response.statusCode());
        }
    }

    /**
     * Posts a JSON body to the configured webhook.
     *
     * @param json serialized payload
     * @return the raw response, including non-2xx statuses
     * @throws IOException when the sink is stopped or the network fails
     */
    private HttpResponse<String> execute(String json) throws IOException, InterruptedException {
        HttpClient current = client;
        if (current == null) {
            throw new IOException("http sink is stopped");
        }
        HttpRequest request = HttpRequest.newBuilder(outboundUri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        return current.send(request, HttpResponse.BodyHandlers.ofString());
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

    private static SyncSurface buildSurface() {
        SyncSurface lcm = SyncSurface.lcm("http", SyncSurface.CURRENT_CONTRACT_VERSION);
        Set<SyncActionSpec> actions = new LinkedHashSet<>(lcm.actions());
        actions.add(new SyncActionSpec(
            WEBHOOK_SEND,
            ActionKind.WRITE,
            List.of(SyncParamSpec.required("payload", SyncValueType.OBJECT)),
            null,
            false,
            15_000));
        actions.add(new SyncActionSpec(
            ENDPOINT_INFO,
            ActionKind.READ,
            List.of(),
            SyncParamSpec.optional("info", SyncValueType.OBJECT),
            true,
            5_000));
        return new SyncSurface("http", SyncSurface.CURRENT_CONTRACT_VERSION, actions);
    }

    /**
     * Reads the neutral {@code OBJECT} payload and serializes it as the JSON
     * body of the webhook.
     */
    private static String payloadOf(SyncCall call) {
        Object value = call.args().get("payload");
        if (!(value instanceof Map<?, ?> payload)) {
            throw new IllegalArgumentException("'payload' must be an object map");
        }
        JSONObject json = new JSONObject();
        for (Map.Entry<?, ?> entry : payload.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException("'payload' keys must be non-blank strings");
            }
            json.put(key, JSONObject.wrap(entry.getValue()));
        }
        return json.toString();
    }

    /**
     * Maps an HTTP status to an explicit result. A 2xx response is success;
     * every other status is classified instead of throwing, so the caller sees
     * why the webhook rejected the call.
     */
    private static SyncResult map(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return new SyncResult.Ok(null);
        }
        return switch (status) {
            case 400 -> new SyncResult.InvalidArgument(
                "webhook rejected the payload (HTTP 400)");
            case 401, 403 -> new SyncResult.HostFailure(
                "webhook denied the request (HTTP " + status + ")");
            case 404 -> new SyncResult.NotFound("webhook endpoint not found (HTTP 404)");
            case 409 -> new SyncResult.Conflict("webhook reported a conflict (HTTP 409)");
            case 429 -> new SyncResult.RateLimited(retryAfterMillis(response));
            default -> new SyncResult.HostFailure("webhook HTTP " + status);
        };
    }

    /** @return {@code Retry-After} in milliseconds; {@code 0} when absent or not numeric */
    private static long retryAfterMillis(HttpResponse<String> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isEmpty()) {
            return 0;
        }
        try {
            long seconds = Long.parseLong(header.get().trim());
            return seconds > 0 ? seconds * 1000 : 0;
        } catch (NumberFormatException notSeconds) {
            return 0;
        }
    }

    /**
     * Describes the local endpoint and the outbound target without exposing
     * secrets: no token, no HMAC secret, no URL path or query.
     */
    private Map<String, Object> endpointInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name());
        info.put("inboundBind", bindAddress);
        info.put("inboundPort", inboundPort());
        info.put("inboundPath", inboundPath);
        info.put("inboundRunning", server != null);
        info.put("inboundAuthRequired", authToken != null || hmacSecret != null);
        info.put("inboundHmacEnabled", hmacSecret != null);
        info.put("outboundScheme", outboundUri.getScheme());
        info.put("outboundHost", outboundUri.getHost());
        info.put("outboundPort", outboundUri.getPort());
        return info;
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }
}

package me.majhrs16.suite.syncwebsocket;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.metrics.Metrics;
import me.majhrs16.suite.api.spi.PluginLogger;
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
import me.majhrs16.suite.transport.MessageCodec;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket-based sync sink providing real-time synchronization endpoints:
 * <ul>
 *   <li>{@code /ws/chat} - Chat messages in real-time</li>
 *   <li>{@code /ws/events} - Server events (join, quit, death, advancement)</li>
 *   <li>{@code /ws/sync} - Cross-server sync messages</li>
 *   <li>{@code /ws/logs} - Server logs streaming</li>
 * </ul>
 *
 * <p>Supports subscription filtering, authentication via token, message broadcasting,
 * and protection against oversized payloads and connection flooding.</p>
 *
 * <p>The sink publishes the official least common multiple plus two native
 * actions: {@code path.broadcast} sends a neutral payload to the subscribers of
 * one endpoint, and {@code client.info} reports connection counters without
 * exposing addresses or the auth token.</p>
 */
public final class WebSocketSyncSink implements SyncSink {

    private static final int DEFAULT_PORT = 9092;
    private static final String CHAT_PATH = "/ws/chat";
    private static final String EVENTS_PATH = "/ws/events";
    private static final String SYNC_PATH = "/ws/sync";
    private static final String LOGS_PATH = "/ws/logs";
    private static final int MAX_MESSAGE_SIZE = 64 * 1024; // 64KB per message
    private static final int MAX_MESSAGES_PER_SECOND = 100; // Rate limit per connection

    private static final String PATH_BROADCAST = "path.broadcast";
    private static final String CLIENT_INFO = "client.info";
    private static final Set<String> NATIVE_ACTIONS = Set.of(PATH_BROADCAST, CLIENT_INFO);
    private static final Set<String> BROADCAST_PATHS = Set.of(CHAT_PATH, EVENTS_PATH, SYNC_PATH);
    private static final List<String> ALL_PATHS =
        List.of(CHAT_PATH, EVENTS_PATH, SYNC_PATH, LOGS_PATH);
    private static final SyncSurface SURFACE = buildSurface();
    private static final Gson GSON = new Gson();
    private static final long START_TIMEOUT_MILLIS = 5_000;

    private final int port;
    private final String authToken;
    private final String bindAddress;
    private final PluginLogger logger;
    private volatile WebSocketServer server;
    private volatile SyncListener listener;
    private volatile boolean running = false;

    // Subscription management
    private final Map<String, Set<WebSocket>> subscriptions = new ConcurrentHashMap<>();
    private final Map<WebSocket, Set<String>> clientSubscriptions = new ConcurrentHashMap<>();

    // Connected clients
    private final Set<WebSocket> allClients = ConcurrentHashMap.newKeySet();

    public WebSocketSyncSink(int port, String authToken, PluginLogger logger) {
        this(port, authToken, logger, "127.0.0.1");
    }

    /**
     * @param port listen port; {@code 0} binds an ephemeral port and
     *             {@code < 0} falls back to {@value #DEFAULT_PORT}
     */
    public WebSocketSyncSink(int port, String authToken, PluginLogger logger, String bindAddress) {
        this.port = port >= 0 ? port : DEFAULT_PORT;
        this.authToken = authToken;
        this.logger = logger;
        // Bind to localhost by default for security; can be overridden via system property or parameter
        this.bindAddress = System.getProperty("textformattersuite.ws.bind", bindAddress);
    }

    @Override
    public String name() {
        return "websocket";
    }

    @Override
    public SyncSurface surface() {
        return SURFACE;
    }

    @Override
    public DeliverySemantics deliverySemantics() {
        return DeliverySemantics.AT_LEAST_ONCE;
    }

    @Override
    public Ordering ordering() {
        return Ordering.PER_CHANNEL;
    }

    /**
     * Executes the least common multiple through the default implementation and
     * the WebSocket-native actions through this sink.
     *
     * <p>Native actions require a {@link Principal.Program};
     * {@link Principal.Official} only reaches the LCM. A stopped sink rejects
     * both native actions instead of pretending to broadcast.</p>
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
        if (!running) {
            return new SyncResult.HostFailure("websocket sink is stopped");
        }
        if (CLIENT_INFO.equals(call.action())) {
            return clientInfo(call);
        }
        try {
            return broadcast(call);
        } catch (IllegalArgumentException invalid) {
            return new SyncResult.InvalidArgument(invalid.getMessage());
        }
    }

    /**
     * Starts the server. The underlying {@link WebSocketServer} cannot be
     * started twice, so every start builds a fresh instance; the previous one is
     * released by {@link #stop()}. Startup fails explicitly when the token is
     * missing, the bind fails or the selector thread does not become ready.
     */
    @Override
    public synchronized void start() throws IOException {
        if (running) return;

        // Reject startup if no auth token configured (security requirement)
        if (authToken == null || authToken.isBlank()) {
            throw new IOException("WebSocket sync sink cannot start: auth token is required but not configured");
        }

        SyncWebSocketServer created = new SyncWebSocketServer(
            new InetSocketAddress(bindAddress, port));
        created.start();
        boolean started;
        try {
            started = created.awaitStarted(START_TIMEOUT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            releaseServer(created);
            throw new IOException("interrupted while starting the WebSocket server", interrupted);
        }
        if (!started) {
            releaseServer(created);
            throw new IOException("WebSocket server did not start within "
                + START_TIMEOUT_MILLIS + " ms");
        }
        Exception failure = created.startFailure();
        if (failure != null) {
            releaseServer(created);
            throw new IOException("WebSocket server failed to bind "
                + bindAddress + ":" + port, failure);
        }

        server = created;
        running = true;
        logger.info("WebSocket sync server started on port " + boundPort());
        logger.info("Endpoints: " + CHAT_PATH + ", " + EVENTS_PATH + ", " + SYNC_PATH + ", " + LOGS_PATH);
    }

    @Override
    public synchronized void stop() {
        WebSocketServer current = server;
        if (current == null && !running) return;

        running = false;
        server = null;
        // Close all connections
        for (WebSocket client : allClients) {
            try {
                client.close(1001, "Server shutting down");
            } catch (Exception ignored) {}
        }
        allClients.clear();
        subscriptions.clear();
        clientSubscriptions.clear();

        if (current != null) {
            releaseServer(current);
            logger.info("WebSocket sync server stopped");
        }
    }

    private void releaseServer(WebSocketServer target) {
        try {
            target.stop(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while stopping WebSocket server");
        } catch (RuntimeException e) {
            logger.warn("Error stopping WebSocket server: " + e.getMessage());
        }
    }

    @Override
    public void send(Message message) throws IOException, InterruptedException {
        if (!running) {
            throw new IOException("websocket sink is stopped");
        }
        String json = MessageCodec.toJson(message).toString();
        String path = getPathForMessage(message);
        broadcastToPath(path, json);
        Metrics.recordSyncSent("websocket", "success");
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    /**
     * Broadcasts a message to all subscribers of a specific path.
     *
     * @return the number of open subscribers that received the message
     */
    public int broadcastToPath(String path, String jsonMessage) {
        Set<WebSocket> subscribers = subscriptions.get(path);
        if (subscribers == null || subscribers.isEmpty()) {
            return 0;
        }

        int delivered = 0;
        for (WebSocket client : new CopyOnWriteArrayList<>(subscribers)) {
            if (client.isOpen()) {
                try {
                    client.send(jsonMessage);
                    Metrics.recordSyncSent("websocket", "success");
                    delivered++;
                } catch (Exception e) {
                    Metrics.recordSyncSent("websocket", "failed");
                    logger.warn("Failed to send to client: " + e.getMessage());
                }
            }
        }
        return delivered;
    }

    /**
     * Broadcasts to all connected clients regardless of subscription.
     */
    public void broadcastAll(String jsonMessage) {
        for (WebSocket client : allClients) {
            if (client.isOpen()) {
                try {
                    client.send(jsonMessage);
                } catch (Exception ignored) {}
            }
        }
    }

    private String getPathForMessage(Message message) {
        // Route based on message type
        return switch (message.type()) {
            case CHAT, PRIVATE, MENTION -> CHAT_PATH;
            case JOIN, LEAVE, DEATH, ADVANCEMENT -> EVENTS_PATH;
            default -> SYNC_PATH;
        };
    }

    /**
     * Sends a neutral payload to the subscribers of one broadcast endpoint.
     * {@code /ws/logs} is reserved for the diagnostics channel and is not
     * reachable through the action surface.
     */
    private SyncResult broadcast(SyncCall call) {
        String path = pathOf(call);
        if (!BROADCAST_PATHS.contains(path)) {
            throw new IllegalArgumentException(
                "'path' must be one of " + BROADCAST_PATHS);
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", "broadcast");
        envelope.addProperty("path", path);
        envelope.add("payload", payloadOf(call));
        String json = GSON.toJson(envelope);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_SIZE) {
            throw new IllegalArgumentException(
                "payload exceeds " + MAX_MESSAGE_SIZE + " bytes");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("delivered", broadcastToPath(path, json));
        return new SyncResult.Ok(result);
    }

    /** Reports connection counters; never addresses, paths or the auth token. */
    private SyncResult clientInfo(SyncCall call) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name());
        info.put("running", running);
        info.put("port", boundPort());
        info.put("clients", getClientCount());
        Object requested = call.args().get("path");
        if (requested == null) {
            Map<String, Integer> subscribers = new LinkedHashMap<>();
            for (String path : ALL_PATHS) {
                subscribers.put(path, getSubscriptionCount(path));
            }
            info.put("subscribers", subscribers);
            return new SyncResult.Ok(info);
        }
        if (!(requested instanceof String path) || !ALL_PATHS.contains(path)) {
            return new SyncResult.InvalidArgument(
                "'path' must be one of " + ALL_PATHS);
        }
        info.put("path", path);
        info.put("subscribers", getSubscriptionCount(path));
        return new SyncResult.Ok(info);
    }

    private static String pathOf(SyncCall call) {
        Object value = call.args().get("path");
        if (!(value instanceof String path) || path.isBlank()) {
            throw new IllegalArgumentException("'path' must be a non-blank string");
        }
        return path;
    }

    private static JsonElement payloadOf(SyncCall call) {
        Object value = call.args().get("payload");
        if (!(value instanceof Map<?, ?> payload)) {
            throw new IllegalArgumentException("'payload' must be an object map");
        }
        return toJsonValue(payload);
    }

    private static JsonElement toJsonValue(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof String || value instanceof Boolean) {
            return GSON.toJsonTree(value);
        }
        if (value instanceof Number number) {
            boolean finite = !(number instanceof Double d && !Double.isFinite(d))
                && !(number instanceof Float f && !Float.isFinite(f));
            if (!finite) {
                throw new IllegalArgumentException(
                    "'payload' must not contain non-finite numbers");
            }
            return GSON.toJsonTree(number);
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                    throw new IllegalArgumentException(
                        "'payload' keys must be non-blank strings");
                }
                object.add(key, toJsonValue(entry.getValue()));
            }
            return object;
        }
        if (value instanceof Iterable<?> list) {
            JsonArray array = new JsonArray();
            for (Object element : list) {
                array.add(toJsonValue(element));
            }
            return array;
        }
        throw new IllegalArgumentException(
            "'payload' contains an unsupported value type: "
                + value.getClass().getName());
    }

    private static SyncSurface buildSurface() {
        SyncSurface lcm = SyncSurface.lcm(
            "websocket", SyncSurface.CURRENT_CONTRACT_VERSION);
        Set<SyncActionSpec> actions = new LinkedHashSet<>(lcm.actions());
        actions.add(new SyncActionSpec(
            PATH_BROADCAST,
            ActionKind.WRITE,
            List.of(
                SyncParamSpec.required("path", SyncValueType.STRING),
                SyncParamSpec.required("payload", SyncValueType.OBJECT)),
            SyncParamSpec.optional("result", SyncValueType.OBJECT),
            false,
            15_000));
        actions.add(new SyncActionSpec(
            CLIENT_INFO,
            ActionKind.READ,
            List.of(SyncParamSpec.optional("path", SyncValueType.STRING)),
            SyncParamSpec.optional("info", SyncValueType.OBJECT),
            true,
            5_000));
        return new SyncSurface(
            "websocket", SyncSurface.CURRENT_CONTRACT_VERSION, actions);
    }

    // ============================================================
    // WebSocket Server Implementation
    // ============================================================

    private final class SyncWebSocketServer extends WebSocketServer {

        private final Gson gson = new Gson();
        private final CountDownLatch startLatch = new CountDownLatch(1);
        private volatile Exception startFailure;
        // Rate limiting per connection: track message count per fixed 1-second window
        // Key: WebSocket, Value: message count for current window
        private final ConcurrentHashMap<WebSocket, Integer> messageCount = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<WebSocket, Long> windowStartTimes = new ConcurrentHashMap<>();

        public SyncWebSocketServer(InetSocketAddress address) {
            super(address);
            // Set SO_REUSEADDR to allow quick reload/restart
            try {
                setReuseAddr(true);
            } catch (Exception e) {
                logger.debug("Could not set SO_REUSEADDR: " + e.getMessage());
            }
        }

        boolean awaitStarted(long millis) throws InterruptedException {
            return startLatch.await(millis, TimeUnit.MILLISECONDS);
        }

        Exception startFailure() {
            return startFailure;
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            String path = handshake.getResourceDescriptor();
            String token = extractToken(handshake);

            // Validate auth token - REQUIRED for security
            if (authToken == null || authToken.isBlank()) {
                conn.close(4001, "Server misconfigured: no auth token set");
                logger.warn("WebSocket connection rejected: server has no auth token configured from " + conn.getRemoteSocketAddress());
                return;
            }
            if (!token.equals(authToken)) {
                conn.close(4001, "Invalid auth token");
                logger.warn("WebSocket connection rejected: invalid token from " + conn.getRemoteSocketAddress());
                return;
            }

            allClients.add(conn);
            logger.debug("WebSocket connected: " + conn.getRemoteSocketAddress() + " to " + path);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            allClients.remove(conn);
            // Clean up rate limiting data
            windowStartTimes.remove(conn);
            messageCount.remove(conn);
            // Clean up subscriptions
            Set<String> subs = clientSubscriptions.remove(conn);
            if (subs != null) {
                for (String path : subs) {
                    Set<WebSocket> pathSubscribers = subscriptions.get(path);
                    if (pathSubscribers != null) {
                        pathSubscribers.remove(conn);
                    }
                }
            }
            logger.debug("WebSocket closed: " + conn.getRemoteSocketAddress() + " (" + code + ")");
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // Check message size limit (bytes, not chars)
            int messageBytes = message.getBytes(StandardCharsets.UTF_8).length;
            if (messageBytes > MAX_MESSAGE_SIZE) {
                conn.send(createError("Message too large: max " + MAX_MESSAGE_SIZE + " bytes"));
                logger.warn("WebSocket message size limit exceeded from " + conn.getRemoteSocketAddress() + " (" + messageBytes + " bytes)");
                return;
            }

            // Rate limiting per connection (fixed 1-second window aligned to epoch)
            long now = System.currentTimeMillis();
            long windowStart = now / 1000 * 1000; // Align to start of current second

            // Get or initialize the window start for this connection
            long storedWindowStart = windowStartTimes.getOrDefault(conn, windowStart);

            // Check if we're in a new window (fixed window, not sliding)
            if (windowStart != storedWindowStart) {
                // New fixed window - reset counter
                windowStartTimes.put(conn, windowStart);
                messageCount.put(conn, 1);
            } else {
                // Same fixed window - increment counter
                int count = messageCount.merge(conn, 1, Integer::sum);

                if (count > MAX_MESSAGES_PER_SECOND) {
                    conn.send(createError("Rate limit exceeded: max " + MAX_MESSAGES_PER_SECOND + " messages/second"));
                    logger.warn("WebSocket rate limit exceeded from " + conn.getRemoteSocketAddress());
                    return;
                }
            }

            try {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                String action = json.get("action").getAsString();

                switch (action) {
                    case "subscribe" -> handleSubscribe(conn, json);
                    case "unsubscribe" -> handleUnsubscribe(conn, json);
                    case "auth" -> handleAuth(conn, json);
                    case "message" -> handleInboundMessage(conn, json);
                    case "ping" -> conn.send("{\"type\":\"pong\"}");
                    default -> conn.send(createError("Unknown action: " + action));
                }
            } catch (JsonSyntaxException e) {
                conn.send(createError("Invalid JSON: " + e.getMessage()));
            } catch (Exception e) {
                logger.error("Error processing WebSocket message", e);
                conn.send(createError("Internal error"));
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            if (conn == null) {
                // A null connection is a server-level failure: a bind error
                // surfaces here before onStart() and must fail start().
                if (startLatch.getCount() > 0) {
                    startFailure = ex;
                    startLatch.countDown();
                } else {
                    logger.error("WebSocket server error", ex);
                }
                return;
            }
            logger.error("WebSocket error for " + conn.getRemoteSocketAddress(), ex);
        }

        @Override
        public void onStart() {
            startLatch.countDown();
            logger.info("WebSocket server started on port " + getPort());
        }

        private void handleSubscribe(WebSocket conn, JsonObject json) {
            String path = json.get("path").getAsString();
            if (!isValidPath(path)) {
                conn.send(createError("Invalid path: " + path));
                return;
            }

            subscriptions.computeIfAbsent(path, k -> ConcurrentHashMap.newKeySet()).add(conn);
            clientSubscriptions.computeIfAbsent(conn, k -> ConcurrentHashMap.newKeySet()).add(path);

            JsonObject response = new JsonObject();
            response.addProperty("type", "subscribed");
            response.addProperty("path", path);
            conn.send(gson.toJson(response));

            logger.debug("Client subscribed to " + path + ": " + conn.getRemoteSocketAddress());
        }

        private void handleUnsubscribe(WebSocket conn, JsonObject json) {
            String path = json.get("path").getAsString();
            Set<WebSocket> subs = subscriptions.get(path);
            if (subs != null) {
                subs.remove(conn);
            }
            Set<String> pathSubs = clientSubscriptions.get(conn);
            if (pathSubs != null) {
                pathSubs.remove(path);
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", "unsubscribed");
            response.addProperty("path", path);
            conn.send(gson.toJson(response));
        }

        private void handleAuth(WebSocket conn, JsonObject json) {
            String token = json.get("token").getAsString();
            if (authToken != null && !authToken.isBlank() && !authToken.equals(token)) {
                conn.send(createError("Invalid auth token"));
                conn.close(4001, "Invalid auth token");
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("type", "auth");
            response.addProperty("success", true);
            conn.send(gson.toJson(response));
        }

        private void handleInboundMessage(WebSocket conn, JsonObject json) {
            if (listener == null) return;

            try {
                String jsonMessage = json.get("message").getAsString();
                Message message = MessageCodec.fromJson(jsonMessage);
                listener.onMessage(WebSocketSyncSink.this, message);
            } catch (Exception e) {
                logger.error("Failed to process inbound message", e);
                conn.send(createError("Failed to process message: " + e.getMessage()));
            }
        }

        private boolean isValidPath(String path) {
            return CHAT_PATH.equals(path) || EVENTS_PATH.equals(path) ||
                   SYNC_PATH.equals(path) || LOGS_PATH.equals(path);
        }

        private String extractToken(ClientHandshake handshake) {
            // Check query parameter
            String query = handshake.getResourceDescriptor();
            if (query.contains("token=")) {
                String[] parts = query.split("token=");
                if (parts.length > 1) {
                    return parts[1].split("&")[0];
                }
            }
            // Check header
            String auth = handshake.getFieldValue("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                return auth.substring(7);
            }
            return "";
        }

        private String createError(String message) {
            JsonObject error = new JsonObject();
            error.addProperty("type", "error");
            error.addProperty("message", message);
            return gson.toJson(error);
        }
    }

    // ============================================================
    // Public API for external components
    // ============================================================

    /**
     * Sends a log message to all subscribers of /ws/logs.
     */
    public void sendLog(String level, String message) {
        JsonObject log = new JsonObject();
        log.addProperty("type", "log");
        log.addProperty("level", level);
        log.addProperty("message", message);
        log.addProperty("timestamp", System.currentTimeMillis());
        broadcastToPath(LOGS_PATH, new Gson().toJson(log));
    }

    /**
     * Gets the set of currently connected clients.
     */
    public Set<WebSocket> getConnectedClients() {
        return Collections.unmodifiableSet(allClients);
    }

    /**
     * Gets subscription count for a path.
     */
    public int getSubscriptionCount(String path) {
        Set<WebSocket> subs = subscriptions.get(path);
        return subs != null ? subs.size() : 0;
    }

    /**
     * Gets total connected client count.
     */
    public int getClientCount() {
        return allClients.size();
    }

    /** @return the configured port, or the bound port once the server is up */
    public int getPort() {
        return boundPort();
    }

    /** @return the actual listening port; {@code 0} while an ephemeral bind is pending */
    public int boundPort() {
        WebSocketServer current = server;
        return current != null ? current.getPort() : port;
    }

    public boolean isRunning() {
        return running;
    }
}

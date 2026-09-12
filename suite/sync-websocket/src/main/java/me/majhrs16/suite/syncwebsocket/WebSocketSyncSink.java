package me.majhrs16.suite.syncwebsocket;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.transport.MessageCodec;
import me.majhrs16.suite.observability.metrics.MetricsCollector;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * WebSocket-based sync sink providing real-time synchronization endpoints:
 * <ul>
 *   <li>{@code /ws/chat} - Chat messages in real-time</li>
 *   <li>{@code /ws/events} - Server events (join, quit, death, advancement)</li>
 *   <li>{@code /ws/sync} - Cross-server sync messages</li>
 *   <li>{@code /ws/logs} - Server logs streaming</li>
 * </ul>
 *
 * <p>Supports subscription filtering, authentication via token, and message broadcasting.</p>
 */
public final class WebSocketSyncSink implements SyncSink {

    private static final int DEFAULT_PORT = 9092;
    private static final String CHAT_PATH = "/ws/chat";
    private static final String EVENTS_PATH = "/ws/events";
    private static final String SYNC_PATH = "/ws/sync";
    private static final String LOGS_PATH = "/ws/logs";

    private final int port;
    private final String authToken;
    private final PluginLogger logger;
    private final WebSocketServer server;
    private final MessageCodec codec;
    private volatile SyncListener listener;

    // Subscription management
    private final Map<String, Set<WebSocket>> subscriptions = new ConcurrentHashMap<>();
    private final Map<WebSocket, Set<String>> clientSubscriptions = new ConcurrentHashMap<>();

    // Connected clients
    private final Set<WebSocket> allClients = ConcurrentHashMap.newKeySet();

    public WebSocketSyncSink(int port, String authToken, PluginLogger logger) {
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.authToken = authToken;
        this.logger = logger;
        this.codec = new MessageCodec();
        this.server = new SyncWebSocketServer(new InetSocketAddress(port));
    }

    @Override
    public String name() {
        return "websocket";
    }

    @Override
    public synchronized void start() throws IOException {
        if (server.isRunning()) return;

        server.start();
        logger.info("WebSocket sync server started on port " + port);
        logger.info("Endpoints: " + CHAT_PATH + ", " + EVENTS_PATH + ", " + SYNC_PATH + ", " + LOGS_PATH);
    }

    @Override
    public synchronized void stop() {
        if (!server.isRunning()) return;

        // Close all connections
        for (WebSocket client : allClients) {
            try {
                client.close(1001, "Server shutting down");
            } catch (Exception ignored) {}
        }
        allClients.clear();
        subscriptions.clear();
        clientSubscriptions.clear();

        try {
            server.stop(1000);
        } catch (Exception e) {
            logger.warn("Error stopping WebSocket server: " + e.getMessage());
        }
        logger.info("WebSocket sync server stopped");
    }

    @Override
    public void send(Message message) throws IOException, InterruptedException {
        String json = MessageCodec.toJson(message).toString();
        String path = getPathForMessage(message);
        broadcastToPath(path, json);
        MetricsCollector.recordSyncSent("websocket", "success");
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    /**
     * Broadcasts a message to all subscribers of a specific path.
     */
    public void broadcastToPath(String path, String jsonMessage) {
        Set<WebSocket> subscribers = subscriptions.get(path);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        for (WebSocket client : new CopyOnWriteArrayList<>(subscribers)) {
            if (client.isOpen()) {
                try {
                    client.send(jsonMessage);
                    MetricsCollector.recordSyncSent("websocket", "success");
                } catch (Exception e) {
                    MetricsCollector.recordSyncSent("websocket", "failed");
                    logger.warn("Failed to send to client: " + e.getMessage());
                }
            }
        }
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

    // ============================================================
    // WebSocket Server Implementation
    // ============================================================

    private final class SyncWebSocketServer extends WebSocketServer {

        private final Gson gson = new Gson();

        public SyncWebSocketServer(InetSocketAddress address) {
            super(address);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            String path = handshake.getResourceDescriptor();
            String token = extractToken(handshake);

            // Validate auth token if configured
            if (authToken != null && !authToken.isBlank()) {
                if (!token.equals(authToken)) {
                    conn.close(4001, "Invalid auth token");
                    logger.warn("WebSocket connection rejected: invalid token from " + conn.getRemoteSocketAddress());
                    return;
                }
            }

            allClients.add(conn);
            logger.debug("WebSocket connected: " + conn.getRemoteSocketAddress() + " to " + path);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            allClients.remove(conn);
            // Clean up subscriptions
            Set<String> subs = clientSubscriptions.remove(conn);
            if (subs != null) {
                for (String path : subs) {
                    subscriptions.getOrDefault(path, Set.of()).remove(conn);
                }
            }
            logger.debug("WebSocket closed: " + conn.getRemoteSocketAddress() + " (" + code + ")");
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
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
            logger.error("WebSocket error for " + conn.getRemoteSocketAddress(), ex);
        }

        @Override
        public void onStart() {
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
            Set<String> subs = clientSubscriptions.get(conn);
            if (subs != null) {
                subs.remove(path);
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

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return server.isRunning();
    }
}
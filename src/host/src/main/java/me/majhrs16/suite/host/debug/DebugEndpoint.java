package me.majhrs16.suite.host.debug;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.DispatchOutcome;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.PluginLogger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Debug endpoints for the suite (binds to localhost only, requires auth token):
 * - /debug/dump - dumps current state (channels and pipeline availability)
 * - /debug/state - current internal state snapshot
 * - /debug/channels - channel information
 * - /debug/simulate - simulates a message through the full pipeline (requires auth)
 *
 * <p>Security: binds to 127.0.0.1 only, requires X-Debug-Token header matching
 * the configured token. The /debug/simulate endpoint processes messages through
 * the real {@link MessageDispatch} pipeline for load testing.</p>
 */
public final class DebugEndpoint {

    private final HttpServer server;
    private final MessageDispatch dispatch;
    private final ChannelRegistry channels;
    private final PluginLogger logger;
    private final String authToken;
    private final int port;
    private volatile boolean running = false;
    private final long startTime = System.currentTimeMillis();

    private final Map<String, Object> lastSimulationResult = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public DebugEndpoint(MessageDispatch dispatch, ChannelRegistry channels,
                         PluginLogger logger, String authToken) throws IOException {
        this(dispatch, channels, logger, authToken, 9091);
    }

    public DebugEndpoint(MessageDispatch dispatch, ChannelRegistry channels,
                         PluginLogger logger, String authToken, int port) throws IOException {
        this.dispatch = dispatch;
        this.channels = channels;
        this.logger = logger;
        this.port = port;
        // Require auth token - generate one if not provided
        this.authToken = authToken != null && !authToken.isBlank()
            ? authToken
            : java.util.UUID.randomUUID().toString();

        this.executor = Executors.newFixedThreadPool(4);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.setExecutor(executor);

        // Debug endpoints (read-only, no message injection)
        server.createContext("/debug/dump", new DumpHandler());
        server.createContext("/debug/state", new StateHandler());
        server.createContext("/debug/channels", new ChannelsHandler());

        // Simulation endpoint - processes message through the real pipeline
        server.createContext("/debug/simulate", new SimulateHandler());
    }

    public synchronized void start() {
        if (running) return;
        server.start();
        running = true;
        logger.info("Debug endpoint started on port " + server.getAddress().getPort());
        if (authToken != null && authToken.length() > 8) {
            logger.info("Debug auth token: " + authToken.substring(0, 8) + "...");
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        server.stop(0);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public Map<String, Object> getLastSimulationResult() {
        return Map.copyOf(lastSimulationResult);
    }

    private boolean checkAuth(HttpExchange exchange) throws IOException {
        String token = exchange.getRequestHeaders().getFirst("X-Debug-Token");
        if (!authToken.equals(token)) {
            sendResponse(exchange, 401, "Invalid auth token", "text/plain");
            return false;
        }
        return true;
    }

    // ============================================================
    // Handlers
    // ============================================================

    private final class DumpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed", "text/plain");
                return;
            }

            Map<String, Object> dump = Map.of(
                "host", Map.of(
                    "name", "TextFormatter Suite",
                    "version", "1.0.0"
                ),
                "channels", channels.paths(),
                "timestamp", java.time.Instant.now().toString()
            );

            String response = toJson(dump);
            sendResponse(exchange, 200, response, "application/json");
        }
    }

    private final class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed", "text/plain");
                return;
            }

            Map<String, Object> state = Map.of(
                "host", "TextFormatter Suite",
                "version", "1.0.0",
                "uptimeMs", System.currentTimeMillis() - startTime,
                "channelsLoaded", channels.paths().size(),
                "timestamp", java.time.Instant.now().toString()
            );

            String response = toJson(state);
            sendResponse(exchange, 200, response, "application/json");
        }
    }

    private final class ChannelsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed", "text/plain");
                return;
            }

            List<Map<String, Object>> channelList = channels.paths().stream()
                .map(path -> {
                    var channel = channels.resolve(path);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", channel.name());
                    m.put("permission", channel.permission());
                    m.put("sendPermission", channel.sendPermission());
                    m.put("receivePermission", channel.receivePermission());
                    m.put("type", channel.type().name());
                    m.put("showSender", channel.showSender());
                    m.put("rateLimit", channel.rateLimitPerSecond());
                    m.put("langSource", channel.langSource().code());
                    m.put("langTarget", channel.langTarget().code());
                    m.put("messageCount", channel.messages().texts().length);
                    m.put("tooltipCount", channel.tooltips().texts().length);
                    m.put("soundCount", channel.sounds().size());
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());

            String response = toJson(Map.of("channels", channelList));
            sendResponse(exchange, 200, response, "application/json");
        }
    }

    /**
     * Handler for /debug/simulate - processes a message through the real
     * dispatch pipeline (routing, formatting, translation, delivery).
     * Used for load testing the complete message path.
     */
    private final class SimulateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed", "text/plain");
                return;
            }

            // Read JSON body
            String requestBody;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                requestBody = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            // Parse JSON (simple parsing for load test)
            Map<String, String> params = parseJsonBody(requestBody);
            if (params.isEmpty()) {
                sendResponse(exchange, 400, "Invalid JSON body", "text/plain");
                return;
            }

            // Build message from parameters
            String typeStr = params.getOrDefault("type", "CHAT");
            String channelName = params.getOrDefault("channel", "chat.global");
            String senderName = params.getOrDefault("sender", "LoadTestPlayer");
            String content = params.getOrDefault("content", "Load test message");
            String sourceLangStr = params.getOrDefault("sourceLang", "auto");
            String directionStr = params.getOrDefault("direction", "OTHERS");

            try {
                // Resolve channel
                channels.resolve(channelName);

                // Create sender actor
                Actor sender = new Actor(
                    UUID.randomUUID(),
                    senderName,
                    Actor.ActorKind.PLAYER,
                    Language.fromCode(sourceLangStr).orElse(Language.AUTO),
                    null
                );

                // Parse direction
                Direction direction;
                switch (directionStr.toUpperCase()) {
                    case "INITIATOR" -> direction = Direction.initiator();
                    case "OTHERS" -> direction = Direction.others();
                    case "ALL" -> direction = Direction.all();
                    case "CONSOLE" -> direction = Direction.console();
                    default -> direction = Direction.others();
                }

                // Build message
                Message message = Message.builder()
                    .type(MessageType.valueOf(typeStr.toUpperCase()))
                    .sender(sender)
                    .direction(direction)
                    .translate(true)
                    .text(content)
                    .channel(channelName)
                    .build();

                // Dispatch through the real pipeline
                DispatchOutcome outcome = dispatch.dispatchAndReport(message);

                // Capture result
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", outcome.status().name());
                result.put("considered", outcome.considered());
                result.put("delivered", outcome.delivered());
                result.put("silenced", outcome.silenced());
                result.put("redirected", outcome.redirected());
                result.put("failed", outcome.failed());
                result.put("message", content);
                lastSimulationResult.putAll(result);

                String response = toJson(result);
                sendResponse(exchange, 200, response, "application/json");

            } catch (Exception e) {
                logger.error("Simulation failed: " + e.getMessage(), e);
                sendResponse(exchange, 500, "Simulation error: " + e.getMessage(), "text/plain");
            }
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private Map<String, String> parseJsonBody(String body) {
        Map<String, String> result = new java.util.HashMap<>();
        if (body == null || body.isBlank()) return result;

        // Simple JSON parsing without Jackson
        String cleaned = body.trim();
        if (!cleaned.startsWith("{") || !cleaned.endsWith("}")) return result;
        cleaned = cleaned.substring(1, cleaned.length() - 1);

        for (String pair : cleaned.split(",")) {
            int colonIdx = pair.indexOf(':');
            if (colonIdx > 0) {
                String key = pair.substring(0, colonIdx).trim().replaceAll("^\"|\"$", "");
                String value = pair.substring(colonIdx + 1).trim().replaceAll("^\"|\"$", "");
                result.put(key, value);
            }
        }
        return result;
    }

    private void sendResponse(HttpExchange exchange, int code, String body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // Removed: Access-Control-Allow-Origin: * (security)
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String toJson(Object obj) {
        try {
            // Use a simple JSON approach without Jackson dependency
            return toJsonSimple(obj);
        } catch (Exception e) {
            return "{\"error\": \"JSON serialization failed: " + e.getMessage() + "\"}";
        }
    }

    private String toJsonSimple(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return "\"" + obj + "\"";
        if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":");
                sb.append(toJsonSimple(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) obj) {
                if (!first) sb.append(",");
                sb.append(toJsonSimple(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + obj.toString() + "\"";
    }
}

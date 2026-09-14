package me.majhrs16.suite.observability.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Debug endpoints for the suite (binds to localhost only, requires auth token):
 * - /debug/dump - dumps current state (channels, rules, sinks, etc.)
 * - /debug/state - current internal state snapshot
 * - /debug/channels - channel information
 * - /debug/rules - iFlow rules (stub)
 * - /debug/sinks - sync sinks (stub)
 *
 * <p>Security: binds to 127.0.0.1 only, requires X-Debug-Token header matching
 * the configured token. The /debug/simulate endpoint has been removed to prevent
 * remote message injection.</p>
 */
public final class DebugEndpoint {

    private final HttpServer server;
    private final SuiteHost host;
    private final MessageDispatcher dispatcher;
    private final ChannelRegistry channels;
    private final PluginLogger logger;
    private final String authToken;
    private volatile boolean running = false;
    private final long startTime = System.currentTimeMillis();

    private final Map<String, Object> lastSimulationResult = new ConcurrentHashMap<>();

    public DebugEndpoint(SuiteHost host, MessageDispatcher dispatcher,
                         ChannelRegistry channels, PluginLogger logger, String authToken) throws IOException {
        this.host = host;
        this.dispatcher = dispatcher;
        this.channels = channels;
        this.logger = logger;
        this.authToken = authToken;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 9091), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));

        // Debug endpoints (read-only, no message injection)
        server.createContext("/debug/dump", new DumpHandler());
        server.createContext("/debug/state", new StateHandler());
        server.createContext("/debug/channels", new ChannelsHandler());
        server.createContext("/debug/rules", new RulesHandler());
        server.createContext("/debug/sinks", new SinksHandler());

        // Removed: server.setExecutor(Executors.newFixedThreadPool(4)); // duplicate
    }

    public synchronized void start() {
        if (running) return;
        server.start();
        running = true;
        System.out.println("Debug endpoint started on port " + server.getAddress().getPort());
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        server.stop(0);
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
        if (authToken == null || authToken.isBlank()) {
            sendResponse(exchange, 403, "Debug endpoint disabled: no auth token configured", "text/plain");
            return false;
        }
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
                    "config", host.config(),
                    "translation", host.translation().activeName(),
                    "channelsCount", channels.paths().size()
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
                "version", "2.1.0-SNAPSHOT",
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
                    return Map.of(
                        "name", channel.name(),
                        "permission", channel.permission(),
                        "sendPermission", channel.sendPermission(),
                        "receivePermission", channel.receivePermission(),
                        "type", channel.type().name(),
                        "showSender", channel.showSender(),
                        "rateLimit", channel.rateLimitPerSecond(),
                        "langSource", channel.langSource().code(),
                        "langTarget", channel.langTarget().code(),
                        "messageCount", channel.messages().templates().length,
                        "tooltipCount", channel.tooltips().templates().length,
                        "soundCount", channel.sounds().size()
                    );
                })
                .toList();

            String response = toJson(Map.of("channels", channelList));
            sendResponse(exchange, 200, response, "application/json");
        }
    }

    private final class RulesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed", "text/plain");
                return;
            }

            // Access rules via reflection or expose via host
            String response = toJson(Map.of(
                "note", "Rules dump not yet implemented - requires access to iFlow router"
            ));
            sendResponse(exchange, 200, response, "application/json");
        }
    }

    private final class SinksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) return;
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed", "text/plain");
                return;
            }

            String response = toJson(Map.of(
                "note", "Sinks dump not yet implemented - requires access to host sinks"
            ));
            sendResponse(exchange, 200, response, "application/json");
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private Map<String, String> parseParams(HttpExchange exchange) throws IOException {
        Map<String, String> params = new java.util.HashMap<>();

        // Parse query string
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
            }
        }

        return params;
    }

    private int getChannelRedirected(me.majhrs16.suite.host.DispatchReport report) {
        try {
            var field = me.majhrs16.suite.host.DispatchReport.class.getDeclaredField("channelRedirected");
            field.setAccessible(true);
            return (int) field.get(report);
        } catch (Exception e) {
            return 0;
        }
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
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\": \"JSON serialization failed: " + e.getMessage() + "\"}";
        }
    }
}
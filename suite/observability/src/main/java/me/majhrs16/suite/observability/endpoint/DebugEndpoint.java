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
 * Debug endpoints for the suite:
 * - /debug/simulate - simulate message processing with given parameters
 * - /debug/dump - dumps current state (channels, rules, sinks, etc.)
 * - /debug/state - current internal state snapshot
 */
public final class DebugEndpoint {

    private final HttpServer server;
    private final SuiteHost host;
    private final MessageDispatcher dispatcher;
    private final ChannelRegistry channels;
    private final PluginLogger logger;
    private volatile boolean running = false;

    private final Map<String, Object> lastSimulationResult = new ConcurrentHashMap<>();

    public DebugEndpoint(SuiteHost host, MessageDispatcher dispatcher,
                         ChannelRegistry channels, PluginLogger logger) throws IOException {
        this.host = host;
        this.dispatcher = dispatcher;
        this.channels = channels;
        this.logger = logger;
        this.server = HttpServer.create(new InetSocketAddress(9091), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));

        // Debug endpoints
        server.createContext("/debug/simulate", new SimulateHandler());
        server.createContext("/debug/dump", new DumpHandler());
        server.createContext("/debug/state", new StateHandler());
        server.createContext("/debug/channels", new ChannelsHandler());
        server.createContext("/debug/rules", new RulesHandler());
        server.createContext("/debug/sinks", new SinksHandler());

        server.setExecutor(Executors.newFixedThreadPool(4));
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

    public Map<String, Object> getLastSimulationResult() {
        return Map.copyOf(lastSimulationResult);
    }

    // ============================================================
    // Handlers
    // ============================================================

    private final class SimulateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod()) && !"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed", "text/plain");
                return;
            }

            // Parse query parameters or JSON body
            Map<String, String> params = parseParams(exchange);

            String type = params.getOrDefault("type", "CHAT");
            String channel = params.getOrDefault("channel", "chat.global");
            String sender = params.getOrDefault("sender", "TestPlayer");
            String content = params.getOrDefault("content", "Hello world!");
            String sourceLang = params.getOrDefault("sourceLang", "auto");
            String targetLang = params.getOrDefault("targetLang", "en");
            String direction = params.getOrDefault("direction", "OTHERS");

            // Create test message
            Actor testSender = new Actor(
                UUID.randomUUID(), sender, Actor.ActorKind.PLAYER,
                Language.of(sourceLang).orElse(Language.AUTO), null
            );

            Message message = Message.builder()
                .type(MessageType.valueOf(type.toUpperCase()))
                .sender(testSender)
                .direction(Direction.valueOf(direction.toUpperCase()))
                .translate(true)
                .text(content)
                .channel(channel)
                .build();

            // Simulate through dispatcher
            long start = System.nanoTime();
            var result = dispatcher.dispatch(message);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // Store result
            lastSimulationResult.clear();
            lastSimulationResult.put("input", Map.of(
                "type", type, "channel", channel, "sender", sender,
                "content", content, "sourceLang", sourceLang,
                "targetLang", targetLang, "direction", direction
            ));
            lastSimulationResult.put("result", Map.of(
                "considered", result.considered(),
                "delivered", result.delivered(),
                "silenced", result.silenced(),
                "redirected", result.redirected(),
                "channelRedirected", getChannelRedirected(result),
                "elapsedMs", elapsedMs
            ));
            lastSimulationResult.put("timestamp", java.time.Instant.now().toString());

            String response = toJson(lastSimulationResult);
            sendResponse(exchange, 200, response, "application/json");
        }
    }

    private final class DumpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
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
                "simulation", getLastSimulationResult(),
                "timestamp", java.time.Instant.now().toString()
            );

            String response = toJson(dump);
            sendResponse(exchange, 200, response, "application/json");
        }
    }

    private final class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed", "text/plain");
                return;
            }

            Map<String, Object> state = Map.of(
                "host", "TextFormatter Suite",
                "version", "2.1.0-SNAPSHOT",
                "uptimeMs", System.currentTimeMillis() - System.currentTimeMillis(), // placeholder
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

        // Parse JSON body for POST
        if ("POST".equals(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.isBlank()) {
                try {
                    // Simple JSON parsing for flat objects
                    String json = body.trim();
                    if (json.startsWith("{") && json.endsWith("}")) {
                        json = json.substring(1, json.length() - 1);
                        for (String pair : json.split(",")) {
                            String[] kv = pair.split(":", 2);
                            if (kv.length == 2) {
                                String key = kv[0].trim().replaceAll("\"", "");
                                String value = kv[1].trim().replaceAll("\"", "");
                                params.put(key, value);
                            }
                        }
                    }
                } catch (Exception ignored) {}
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
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
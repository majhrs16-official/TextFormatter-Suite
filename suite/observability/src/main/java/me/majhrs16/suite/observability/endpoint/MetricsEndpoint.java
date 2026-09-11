package me.majhrs16.suite.observability.endpoint;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import me.majhrs16.suite.observability.metrics.MetricsCollector;
import me.majhrs16.suite.api.spi.PluginLogger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Exposes Prometheus metrics endpoint at {@code /metrics}.
 * Runs an embedded HTTP server with a scheduled JVM metrics updater.
 */
public final class MetricsEndpoint {

    private static final int DEFAULT_PORT = 9090;
    private static final String DEFAULT_PATH = "/metrics";

    private final HttpServer server;
    private final ScheduledExecutorService scheduler;
    private final PluginLogger logger;
    private volatile boolean running = false;

    public MetricsEndpoint(PluginLogger logger) throws IOException {
        this(logger, DEFAULT_PORT, DEFAULT_PATH);
    }

    public MetricsEndpoint(PluginLogger logger, int port, String path) throws IOException {
        this.logger = logger;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "textformatter-metrics-updater");
            t.setDaemon(true);
            return t;
        });

        // Metrics endpoint
        server.createContext(path, new MetricsHandler());

        // Health endpoint
        server.createContext("/health", new HealthHandler());

        // Configure executor
        server.setExecutor(Executors.newFixedThreadPool(4));
    }

    /**
     * Starts the metrics endpoint server and periodic JVM metrics updater.
     */
    public synchronized void start() {
        if (running) return;
        server.start();
        running = true;

        // Update JVM metrics every 15 seconds
        scheduler.scheduleAtFixedRate(() -> {
            try {
                MetricsCollector.updateJvmMetrics();
            } catch (Exception e) {
                logger.warn("Failed to update JVM metrics: " + e.getMessage());
            }
        }, 15, 15, TimeUnit.SECONDS);

        logger.info("Metrics endpoint started on port " + server.getAddress().getPort() + " at " + server.getAddress().getPort());
    }

    /**
     * Stops the metrics endpoint server.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        server.stop(0);
        logger.info("Metrics endpoint stopped");
    }

    /**
     * Handles the /metrics endpoint.
     */
    private final class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            MetricsCollector.updateJvmMetrics();

            String output = TextFormat.write004(MetricsCollector.registry().metricFamilySamples());

            exchange.getResponseHeaders().set("Content-Type", TextFormat.CONTENT_TYPE_004);
            exchange.sendResponseHeaders(200, output.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(output.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Handles the /health endpoint.
     */
    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String healthJson = """
                {
                  "status": "UP",
                  "timestamp": "%s",
                  "uptime_seconds": %d
                }
                """.formatted(
                    java.time.Instant.now().toString(),
                    (System.currentTimeMillis() - MetricsCollector.startTime()) / 1000
                );

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, healthJson.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(healthJson.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    public boolean isRunning() {
        return running;
    }
}
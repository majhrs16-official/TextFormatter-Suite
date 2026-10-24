package me.majhrs16.suite.observability.endpoint;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.observability.Observability;
import me.majhrs16.suite.observability.health.HealthCheckRegistry;
import me.majhrs16.suite.observability.metrics.MetricsCollector;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests de integración para {@link MetricsEndpoint}.
 * Validan el endpoint /metrics, /health y la actualización de métricas JVM.
 */
@DisplayName("MetricsEndpoint — integración")
class MetricsEndpointIntegrationTest {

    private MetricsEndpoint metricsEndpoint;
    private PluginLogger logger;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws IOException {
        logger = new PluginLogger() {
            @Override public void info(String m, Object... a) {}
            @Override public void warn(String m, Object... a) {}
            @Override public void error(String m, Object... a) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m, Object... a) {}
        };

        // Use ephemeral port (0)
        metricsEndpoint = new MetricsEndpoint(logger, 0, "/metrics", "127.0.0.1");
        metricsEndpoint.start();

        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @AfterEach
    void tearDown() {
        if (metricsEndpoint != null) {
            metricsEndpoint.stop();
        }
    }

    @Test
    @DisplayName("inicia y detiene correctamente")
    void startsAndStops() {
        assertThat(metricsEndpoint.isRunning()).isTrue();
        assertThat(metricsEndpoint.getPort()).isGreaterThan(0);

        metricsEndpoint.stop();
        assertThat(metricsEndpoint.isRunning()).isFalse();
    }

    @Test
    @DisplayName("endpoint /metrics expone métricas en formato Prometheus")
    void metricsEndpointReturnsPrometheusFormat() throws Exception {
        int port = metricsEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/metrics"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .contains("text/plain");
        assertThat(response.body()).contains("jvm_");
    }

    @Test
    @DisplayName("endpoint /health devuelve estado UP")
    void healthEndpointReturnsUp() throws Exception {
        int port = metricsEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/health"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .contains("application/json");
        assertThat(response.body()).contains("\"status\": \"UP\"");
        assertThat(response.body()).contains("uptime_seconds");
    }

    @Test
    @DisplayName("rechaza métodos no GET en /metrics")
    void rejectsNonGetOnMetrics() throws Exception {
        int port = metricsEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/metrics"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("rechaza métodos no GET en /health")
    void rejectsNonGetOnHealth() throws Exception {
        int port = metricsEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/health"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("actualiza métricas JVM periódicamente")
    void updatesJvmMetrics() throws Exception {
        int port = metricsEndpoint.getPort();
        
        // First request
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/metrics"))
            .GET()
            .build();

        HttpResponse<String> response1 = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response1.statusCode()).isEqualTo(200);
        
        // Wait for scheduled update (15 seconds) - but we can trigger manually
        MetricsCollector.updateJvmMetrics();
        
        HttpResponse<String> response2 = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response2.statusCode()).isEqualTo(200);
        
        // Both should have jvm metrics
        assertThat(response1.body()).contains("jvm_memory_");
        assertThat(response2.body()).contains("jvm_memory_");
    }

    @Test
    @DisplayName("bind address configurable via system property")
    void bindAddressConfigurable() throws IOException {
        System.setProperty("textformattersuite.metrics.bind", "127.0.0.1");
        try {
            MetricsEndpoint endpoint = new MetricsEndpoint(logger, 0, "/metrics", "0.0.0.0");
            assertThat(endpoint.isRunning()).isFalse(); // Not started yet
            endpoint.start();
            assertThat(endpoint.isRunning()).isTrue();
            assertThat(endpoint.getPort()).isGreaterThan(0);
            endpoint.stop();
        } finally {
            System.clearProperty("textformattersuite.metrics.bind");
        }
    }
}
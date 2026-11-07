package me.majhrs16.suite.host.debug;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.spi.DispatchOutcome;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.pipeline.PipelineStatus;
import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests de integración para {@link DebugEndpoint}.
 * Validan endpoints /debug/*, autenticación, y simulación de mensajes.
 */
@DisplayName("DebugEndpoint — integración")
class DebugEndpointIntegrationTest {

    private DebugEndpoint debugEndpoint;
    private MessageDispatch dispatcher;
    private ChannelRegistry channels;
    private PluginLogger logger;
    private HttpClient httpClient;
    private String authToken;

    @BeforeEach
    void setUp() throws IOException {
        dispatcher = mock(MessageDispatch.class);
        channels = mock(ChannelRegistry.class);
        logger = mock(PluginLogger.class);
        authToken = "test-auth-token-12345";

        Channel channel = mock(Channel.class);
        when(channel.name()).thenReturn("chat.global");
        when(channel.permission()).thenReturn("suite.chat");
        when(channel.sendPermission()).thenReturn(null);
        when(channel.receivePermission()).thenReturn(null);
        when(channel.type()).thenReturn(Channel.Type.CHAT);
        when(channel.showSender()).thenReturn(true);
        when(channel.rateLimitPerSecond()).thenReturn(5);
        when(channel.langSource()).thenReturn(Language.AUTO);
        when(channel.langTarget()).thenReturn(Language.AUTO);
        when(channel.messages()).thenReturn(Formats.empty());
        when(channel.tooltips()).thenReturn(Formats.empty());
        when(channel.sounds()).thenReturn(List.of());

        when(channels.paths()).thenReturn(List.of("chat.global"));
        when(channels.resolve("chat.global")).thenReturn(channel);

        when(dispatcher.dispatchAndReport(any(Message.class)))
            .thenReturn(new DispatchOutcome(PipelineStatus.READY, 1, 1, 0, 0, 0));

        debugEndpoint = new DebugEndpoint(dispatcher, channels, logger, authToken, 0);
        debugEndpoint.start();

        httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();
    }

    @AfterEach
    void tearDown() {
        if (debugEndpoint != null) {
            debugEndpoint.stop();
        }
    }

    @Test
    @DisplayName("inicia y detiene correctamente")
    void startsAndStops() {
        assertThat(debugEndpoint.isRunning()).isTrue();
        assertThat(debugEndpoint.getPort()).isGreaterThan(0);

        debugEndpoint.stop();
        assertThat(debugEndpoint.isRunning()).isFalse();
    }

    @Test
    @DisplayName("requiere autenticación para endpoints de debug")
    void requiresAuthForDebugEndpoints() throws Exception {
        int port = debugEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/debug/state"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Invalid auth token");
    }

    @Test
    @DisplayName("endpoint /debug/state devuelve estado con token válido")
    void debugStateWithValidToken() throws Exception {
        int port = debugEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/debug/state"))
            .header("X-Debug-Token", authToken)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .contains("application/json");
        assertThat(response.body()).contains("host");
        assertThat(response.body()).contains("TextFormatter Suite");
        assertThat(response.body()).contains("uptimeMs");
    }

    @Test
    @DisplayName("endpoint /debug/dump devuelve dump completo")
    void debugDumpWithValidToken() throws Exception {
        int port = debugEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/debug/dump"))
            .header("X-Debug-Token", authToken)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"host\"");
        assertThat(response.body()).contains("\"channels\"");
        assertThat(response.body()).contains("\"chat.global\"");
    }

    @Test
    @DisplayName("endpoint /debug/channels devuelve información de canales")
    void debugChannelsWithValidToken() throws Exception {
        int port = debugEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/debug/channels"))
            .header("X-Debug-Token", authToken)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("channels");
        assertThat(response.body()).contains("chat.global");
        assertThat(response.body()).contains("CHAT");
    }

    @Test
    @DisplayName("rechaza métodos no GET en endpoints de solo lectura")
    void rejectsNonGetOnReadOnlyEndpoints() throws Exception {
        int port = debugEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/debug/state"))
            .header("X-Debug-Token", authToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("endpoint /debug/simulate procesa mensaje a través del pipeline")
    void simulateProcessesMessageThroughPipeline() throws Exception {
        int port = debugEndpoint.getPort();
        String jsonBody = """
            {
              "type": "CHAT",
              "channel": "chat.global",
              "sender": "TestPlayer",
              "content": "Test message",
              "sourceLang": "en",
              "targetLang": "es",
              "direction": "OTHERS"
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/debug/simulate"))
            .header("X-Debug-Token", authToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("considered");
        assertThat(response.body()).contains("delivered");
        verify(dispatcher).dispatchAndReport(any(Message.class));
    }

    @Test
    @DisplayName("simulate requiere método POST")
    void simulateRequiresPost() throws Exception {
        int port = debugEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/debug/simulate"))
            .header("X-Debug-Token", authToken)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("simulate rechaza JSON inválido")
    void simulateRejectsInvalidJson() throws Exception {
        int port = debugEndpoint.getPort();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/debug/simulate"))
            .header("X-Debug-Token", authToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("invalid json"))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("último resultado de simulación disponible vía API")
    void lastSimulationResultAvailableViaApi() throws Exception {
        int port = debugEndpoint.getPort();
        String jsonBody = """
            {
              "type": "CHAT",
              "channel": "chat.global",
              "content": "test"
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/debug/simulate"))
            .header("X-Debug-Token", authToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Check the last simulation result via API
        Map<String, Object> lastResult = debugEndpoint.getLastSimulationResult();
        assertThat(lastResult).containsKey("considered");
        assertThat(lastResult).containsKey("delivered");
    }

    @Test
    @DisplayName("genera token automáticamente si no se proporciona")
    void generatesTokenAutomaticallyIfNotProvided() throws IOException {
        DebugEndpoint noTokenEndpoint = new DebugEndpoint(dispatcher, channels, logger, null, 0);
        noTokenEndpoint.start();

        assertThat(noTokenEndpoint.isRunning()).isTrue();
        
        // The endpoint should have generated a token
        noTokenEndpoint.stop();
    }
}
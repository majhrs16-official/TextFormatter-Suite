package me.majhrs16.suite.gtranslate;

import me.majhrs16.suite.api.spi.TranslationException;
import me.majhrs16.suite.transport.HttpTransport;
import me.majhrs16.suite.transport.Transport;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests de integración para {@link GTranslate} con {@link HttpTransport}.
 * Validan la traducción y detección de idioma contra un endpoint HTTP mock.
 */
@DisplayName("GTranslate + HttpTransport — integración")
class GTranslateIntegrationTest {

    private WireMockServer wireMock;
    private Transport transport;
    private GTranslate translator;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        String localBase = "http://localhost:" + wireMock.port() + "/translate_a/single";

        // Use HttpTransport with SSRF disabled for localhost testing
        HttpTransport httpTransport = new HttpTransport(Duration.ofSeconds(5), false);

        // Wrapper transport that rewrites Google Translate URLs to WireMock
        transport = new Transport() {
            private static final String GOOGLE_BASE = "https://translate.googleapis.com/translate_a/single";

            @Override
            public String get(String url) throws java.io.IOException {
                String rewrittenUrl = url.replace(GOOGLE_BASE, localBase);
                return httpTransport.get(rewrittenUrl);
            }

            @Override
            public String post(String url, String jsonBody) throws java.io.IOException {
                return httpTransport.post(url, jsonBody);
            }

            @Override
            public String post(String url, java.util.Map<String, String> headers, String jsonBody) throws java.io.IOException {
                return httpTransport.post(url, headers, jsonBody);
            }
        };

        translator = new GTranslate(transport);
    }

    @AfterEach
    void tearDown() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    @DisplayName("traduce texto simple correctamente")
    void translatesSimpleText() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/translate_a/single"))
            .withQueryParam("sl", equalTo("en"))
            .withQueryParam("tl", equalTo("es"))
            .withQueryParam("q", equalTo("hello"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[[[\"hola\",\"hello\",null,null,10]],null,\"en\",,,\"GTranslate\"]")));

        String result = translator.translate("hello", "en", "es");

        assertThat(result).isEqualTo("hola");
    }

    @Test
    @DisplayName("traduce múltiples oraciones")
    void translatesMultipleSentences() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/translate_a/single"))
            .withQueryParam("sl", equalTo("en"))
            .withQueryParam("tl", equalTo("es"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[[[\"hola\",\"hello\",null,null,10],[\"mundo\",\"world\",null,null,10]],null,\"en\",,,\"GTranslate\"]")));

        String result = translator.translate("hello world", "en", "es");

        assertThat(result).isEqualTo("holamundo");
    }

    @Test
    @DisplayName("detecta idioma correctamente")
    void detectsLanguage() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/translate_a/single"))
            .withQueryParam("sl", equalTo("auto"))
            .withQueryParam("tl", equalTo("en"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[[[\"hello\",\"hello\",null,null,10]],null,\"es\",,,\"GTranslate\"]")));

        String detected = translator.detect("hola");

        assertThat(detected).isEqualTo("es");
    }

    @Test
    @DisplayName("devuelve \"en\" como fallback en error de detección")
    void detectDefaultsToEnOnError() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/translate_a/single"))
            .willReturn(aResponse().withStatus(500)));

        String detected = translator.detect("hola");

        assertThat(detected).isEqualTo("en");
    }

    @Test
    @DisplayName("lanza TranslationException en error HTTP")
    void throwsTranslationExceptionOnHttpError() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/translate_a/single"))
            .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> translator.translate("hello", "en", "es"))
            .isInstanceOf(TranslationException.class)
            .hasMessageContaining("google translate call failed");
    }

    @Test
    @DisplayName("lanza TranslationException en respuesta vacía")
    void throwsTranslationExceptionOnEmptyResponse() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/translate_a/single"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[]")));

        assertThatThrownBy(() -> translator.translate("hello", "en", "es"))
            .isInstanceOf(TranslationException.class)
            .hasMessageContaining("google translate call failed");
    }

    @Test
    @DisplayName("maneja caracteres especiales y UTF-8")
    void handlesSpecialCharactersAndUtf8() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/translate_a/single"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[[[\"¡Hola, mundo!\",\"Hello, world!\",null,null,10]],null,\"en\",,,\"GTranslate\"]")));

        String result = translator.translate("Hello, world!", "en", "es");

        assertThat(result).isEqualTo("¡Hola, mundo!");
    }

    @Test
    @DisplayName("texto vacío devuelve vacío sin llamar al transporte")
    void emptyTextReturnsEmptyWithoutTransportCall() throws Exception {
        // No stub - should not be called
        String result = translator.translate("", "en", "es");
        assertThat(result).isEqualTo("");
        
        String result2 = translator.translate(null, "en", "es");
        assertThat(result2).isEqualTo("");
    }

    @Test
    @DisplayName("isAvailable devuelve true cuando hay transporte")
    void isAvailableReturnsTrueWithTransport() {
        assertThat(translator.isAvailable()).isTrue();
        assertThat(translator.name()).isEqualTo("google");
    }

    @Test
    @DisplayName("traducción con auto-detección de idioma origen")
    void translateWithAutoSourceDetection() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/translate_a/single"))
            .withQueryParam("sl", equalTo("auto"))
            .withQueryParam("tl", equalTo("es"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[[[\"hola\",\"hello\",null,null,10]],null,\"en\",,,\"GTranslate\"]")));

        String result = translator.translate("hello", "auto", "es");

        assertThat(result).isEqualTo("hola");
    }

    @Test
    @DisplayName("maneja timeouts de red")
    void handlesNetworkTimeouts() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/translate_a/single"))
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(2000))); // 2 second delay

        String localBase = "http://localhost:" + wireMock.port() + "/translate_a/single";
        HttpTransport shortTimeout = new HttpTransport(Duration.ofMillis(100), false);
        Transport timeoutTransport = new Transport() {
            private static final String GOOGLE_BASE = "https://translate.googleapis.com/translate_a/single";

            @Override
            public String get(String url) throws java.io.IOException {
                String rewrittenUrl = url.replace(GOOGLE_BASE, localBase);
                return shortTimeout.get(rewrittenUrl);
            }

            @Override
            public String post(String url, String jsonBody) throws java.io.IOException {
                return shortTimeout.post(url, jsonBody);
            }

            @Override
            public String post(String url, java.util.Map<String, String> headers, String jsonBody) throws java.io.IOException {
                return shortTimeout.post(url, headers, jsonBody);
            }
        };
        GTranslate timeoutTranslator = new GTranslate(timeoutTransport);

        assertThatThrownBy(() -> timeoutTranslator.translate("hello", "en", "es"))
            .isInstanceOf(TranslationException.class)
            .hasMessageContaining("google translate call failed");
    }
}
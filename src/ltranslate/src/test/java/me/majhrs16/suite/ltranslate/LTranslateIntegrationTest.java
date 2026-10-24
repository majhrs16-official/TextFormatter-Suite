package me.majhrs16.suite.ltranslate;

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
 * Tests de integración para {@link LTranslate} con {@link HttpTransport}.
 * Validan la traducción y detección de idioma contra un endpoint HTTP mock.
 */
@DisplayName("LTranslate + HttpTransport — integración")
class LTranslateIntegrationTest {

    private WireMockServer wireMock;
    private Transport transport;
    private LTranslate translator;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        String localBase = "http://localhost:" + wireMock.port();
        String libreBase = "https://libretranslate.example.org";

        // Use HttpTransport with SSRF disabled for localhost testing
        HttpTransport httpTransport = new HttpTransport(Duration.ofSeconds(5), false);

        // Wrapper transport that rewrites LibreTranslate URLs to WireMock
        transport = new Transport() {
            @Override
            public String get(String url) throws java.io.IOException {
                return httpTransport.get(url);
            }

            @Override
            public String post(String url, String jsonBody) throws java.io.IOException {
                String rewrittenUrl = url.replace(libreBase, localBase);
                return httpTransport.post(rewrittenUrl, jsonBody);
            }

            @Override
            public String post(String url, java.util.Map<String, String> headers, String jsonBody) throws java.io.IOException {
                String rewrittenUrl = url.replace(libreBase, localBase);
                return httpTransport.post(rewrittenUrl, headers, jsonBody);
            }
        };

        translator = new LTranslate(libreBase, transport);
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
        wireMock.stubFor(post(urlPathEqualTo("/translate"))
            .withRequestBody(matchingJsonPath("$.q", equalTo("hello")))
            .withRequestBody(matchingJsonPath("$.source", equalTo("en")))
            .withRequestBody(matchingJsonPath("$.target", equalTo("es")))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"translatedText\":\"hola\"}")));

        String result = translator.translate("hello", "en", "es");

        assertThat(result).isEqualTo("hola");
    }

    @Test
    @DisplayName("detecta idioma correctamente")
    void detectsLanguage() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/detect"))
            .withRequestBody(matchingJsonPath("$.q", equalTo("hola")))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"confidence\":0.98,\"language\":\"es\"}]")));

        String detected = translator.detect("hola");

        assertThat(detected).isEqualTo("es");
    }

    @Test
    @DisplayName("devuelve \"en\" como fallback en error de detección")
    void detectDefaultsToEnOnError() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/detect"))
            .willReturn(aResponse().withStatus(500)));

        String detected = translator.detect("hola");

        assertThat(detected).isEqualTo("en");
    }

    @Test
    @DisplayName("lanza TranslationException en error HTTP")
    void throwsTranslationExceptionOnHttpError() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/translate"))
            .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> translator.translate("hello", "en", "es"))
            .isInstanceOf(TranslationException.class)
            .hasMessageContaining("libretranslate translate call failed");
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
        assertThat(translator.name()).isEqualTo("libre");
    }

    @Test
    @DisplayName("normaliza dialectos del proveedor (zh-CN -> zh, pt-BR -> pt)")
    void normalizesProviderDialects() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/translate"))
            .withRequestBody(matchingJsonPath("$.target", equalTo("zh")))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"translatedText\":\"你好\"}")));

        String result = translator.translate("hello", "en", "zh-CN");
        assertThat(result).isEqualTo("你好");

        wireMock.stubFor(post(urlPathEqualTo("/translate"))
            .withRequestBody(matchingJsonPath("$.target", equalTo("pt")))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"translatedText\":\"olá\"}")));

        String result2 = translator.translate("hello", "en", "pt-BR");
        assertThat(result2).isEqualTo("olá");
    }

    @Test
    @DisplayName("envía api_key cuando está configurado")
    void sendsApiKeyWhenConfigured() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/translate"))
            .withRequestBody(matchingJsonPath("$.api_key", equalTo("secret-key")))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"translatedText\":\"hola\"}")));

        LTranslate libreWithKey = new LTranslate("https://libretranslate.example.org", "secret-key", transport);
        String result = libreWithKey.translate("hello", "en", "es");

        assertThat(result).isEqualTo("hola");
    }

    @Test
    @DisplayName("traducción con auto-detección de idioma origen")
    void translateWithAutoSourceDetection() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/translate"))
            .withRequestBody(matchingJsonPath("$.source", equalTo("auto")))
            .withRequestBody(matchingJsonPath("$.target", equalTo("es")))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"translatedText\":\"hola\"}")));

        String result = translator.translate("hello", "auto", "es");

        assertThat(result).isEqualTo("hola");
    }

    @Test
    @DisplayName("maneja timeouts de red")
    void handlesNetworkTimeouts() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/translate"))
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(2000))); // 2 second delay

        String localBase = "http://localhost:" + wireMock.port();
        String libreBase = "https://libretranslate.example.org";
        HttpTransport shortTimeout = new HttpTransport(Duration.ofMillis(100), false);
        Transport timeoutTransport = new Transport() {
            @Override
            public String get(String url) throws java.io.IOException {
                return shortTimeout.get(url);
            }

            @Override
            public String post(String url, String jsonBody) throws java.io.IOException {
                String rewrittenUrl = url.replace(libreBase, localBase);
                return shortTimeout.post(rewrittenUrl, jsonBody);
            }

            @Override
            public String post(String url, java.util.Map<String, String> headers, String jsonBody) throws java.io.IOException {
                String rewrittenUrl = url.replace(libreBase, localBase);
                return shortTimeout.post(rewrittenUrl, headers, jsonBody);
            }
        };
        LTranslate timeoutTranslator = new LTranslate(libreBase, timeoutTransport);

        assertThatThrownBy(() -> timeoutTranslator.translate("hello", "en", "es"))
            .isInstanceOf(TranslationException.class)
            .hasMessageContaining("libretranslate translate call failed");
    }

    @Test
    @DisplayName("maneja respuesta con translatedText vacío")
    void handlesEmptyTranslatedText() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/translate"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"translatedText\":\"\"}")));

        // Should return original text when translation is empty
        String result = translator.translate("hello", "en", "es");

        assertThat(result).isEqualTo("hello");
    }
}
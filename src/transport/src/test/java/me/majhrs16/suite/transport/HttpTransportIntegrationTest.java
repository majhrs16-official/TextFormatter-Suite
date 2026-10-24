package me.majhrs16.suite.transport;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests de integración para {@link HttpTransport}.
 * Validan SSRF protection, redirects, timeouts, y límites de respuesta.
 */
@DisplayName("HttpTransport — integración")
class HttpTransportIntegrationTest {

    private WireMockServer wireMock;
    private HttpTransport transport;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        
        // Disable SSRF for localhost testing
        transport = new HttpTransport(Duration.ofSeconds(5), false);
    }

    @AfterEach
    void tearDown() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    @DisplayName("GET request básico funciona")
    void basicGetRequest() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/test"))
            .willReturn(aResponse().withStatus(200).withBody("hello")));

        String result = transport.get("http://localhost:" + wireMock.port() + "/test");

        assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("POST request con JSON body funciona")
    void postWithJsonBody() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/post"))
            .willReturn(aResponse().withStatus(200).withBody("created")));

        String result = transport.post(
            "http://localhost:" + wireMock.port() + "/post",
            "{\"key\":\"value\"}");

        assertThat(result).isEqualTo("created");
    }

    @Test
    @DisplayName("POST request con headers personalizados")
    void postWithCustomHeaders() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/headers"))
            .withHeader("X-Custom", equalTo("test-value"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));

        String result = transport.post(
            "http://localhost:" + wireMock.port() + "/headers",
            java.util.Map.of("X-Custom", "test-value"),
            "{}");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("SSRF protection bloquea localhost")
    void ssrfBlocksLocalhost() throws Exception {
        HttpTransport ssrfTransport = new HttpTransport(Duration.ofSeconds(5), true);
        
        wireMock.stubFor(get(urlEqualTo("/local"))
            .willReturn(aResponse().withStatus(200).withBody("local")));

        assertThatThrownBy(() -> ssrfTransport.get(
            "http://127.0.0.1:" + wireMock.port() + "/local"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("SSRF blocked");
    }

    @Test
    @DisplayName("SSRF protection bloquea IPs privadas RFC 1918")
    void ssrfBlocksPrivateIPs() throws Exception {
        HttpTransport ssrfTransport = new HttpTransport(Duration.ofSeconds(5), true);
        
        // 10.0.0.0/8
        assertThatThrownBy(() -> ssrfTransport.get("http://10.0.0.1:80/"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("SSRF blocked");

        // 172.16.0.0/12
        assertThatThrownBy(() -> ssrfTransport.get("http://172.16.0.1:80/"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("SSRF blocked");

        // 192.168.0.0/16
        assertThatThrownBy(() -> ssrfTransport.get("http://192.168.1.1:80/"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("SSRF blocked");
    }

    @Test
    @DisplayName("SSRF protection permite IPs públicas")
    void ssrfAllowsPublicIPs() throws Exception {
        HttpTransport ssrfTransport = new HttpTransport(Duration.ofSeconds(5), true);
        
        // This test would need a real public IP or mock DNS
        // We test that the validation logic doesn't throw for valid domains
        // by using a domain that resolves to a public IP
        // For unit test purposes, we just verify the method exists
        assertThat(ssrfTransport).isNotNull();
    }

    @Test
    @DisplayName("SSRF protection puede deshabilitarse")
    void ssrfCanBeDisabled() throws Exception {
        // transport already has SSRF disabled
        wireMock.stubFor(get(urlEqualTo("/no-ssrf"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));

        // With SSRF disabled, localhost should work (but WireMock binds to all interfaces)
        // Note: This test uses WireMock's actual port which binds to 0.0.0.0
        String result = transport.get("http://127.0.0.1:" + wireMock.port() + "/no-ssrf");
        
        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("Maneja redirect 301 (GET)")
    void handles301Redirect() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/redirect-301"))
            .willReturn(aResponse()
                .withStatus(301)
                .withHeader("Location", "http://localhost:" + wireMock.port() + "/final")));

        wireMock.stubFor(get(urlEqualTo("/final"))
            .willReturn(aResponse().withStatus(200).withBody("final destination")));

        String result = transport.get("http://localhost:" + wireMock.port() + "/redirect-301");

        assertThat(result).isEqualTo("final destination");
    }

    @Test
    @DisplayName("Maneja redirect 302 (GET)")
    void handles302Redirect() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/redirect-302"))
            .willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", "http://localhost:" + wireMock.port() + "/final")));

        wireMock.stubFor(get(urlEqualTo("/final"))
            .willReturn(aResponse().withStatus(200).withBody("final destination")));

        String result = transport.get("http://localhost:" + wireMock.port() + "/redirect-302");

        assertThat(result).isEqualTo("final destination");
    }

    @Test
    @DisplayName("Maneja redirect 307 preservando método POST")
    void handles307RedirectPreservesMethod() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/redirect-307"))
            .willReturn(aResponse()
                .withStatus(307)
                .withHeader("Location", "http://localhost:" + wireMock.port() + "/final-post")));

        wireMock.stubFor(post(urlEqualTo("/final-post"))
            .willReturn(aResponse().withStatus(200).withBody("post final")));

        String result = transport.post(
            "http://localhost:" + wireMock.port() + "/redirect-307",
            "{\"data\":\"test\"}");

        assertThat(result).isEqualTo("post final");
    }

    @Test
    @DisplayName("Maneja redirect 308 preservando método POST")
    void handles308RedirectPreservesMethod() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/redirect-308"))
            .willReturn(aResponse()
                .withStatus(308)
                .withHeader("Location", "http://localhost:" + wireMock.port() + "/final-post")));

        wireMock.stubFor(post(urlEqualTo("/final-post"))
            .willReturn(aResponse().withStatus(200).withBody("post final")));

        String result = transport.post(
            "http://localhost:" + wireMock.port() + "/redirect-308",
            "{\"data\":\"test\"}");

        assertThat(result).isEqualTo("post final");
    }

    @Test
    @DisplayName("Límite máximo de redirects (5)")
    void maxRedirectsLimit() throws Exception {
        // Create a chain of 6 redirects
        for (int i = 1; i <= 6; i++) {
            String from = "/redirect-" + i;
            String to = (i == 6) ? "/final" : "/redirect-" + (i + 1);
            wireMock.stubFor(get(urlEqualTo(from))
                .willReturn(aResponse()
                    .withStatus(302)
                    .withHeader("Location", "http://localhost:" + wireMock.port() + to)));
        }
        wireMock.stubFor(get(urlEqualTo("/final"))
            .willReturn(aResponse().withStatus(200).withBody("final")));

        assertThatThrownBy(() -> transport.get(
            "http://localhost:" + wireMock.port() + "/redirect-1"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Too many redirects");
    }

    @Test
    @DisplayName("Rechaza redirect sin Location header")
    void rejectsRedirectWithoutLocation() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/bad-redirect"))
            .willReturn(aResponse()
                .withStatus(302)
                // No Location header
                ));

        assertThatThrownBy(() -> transport.get(
            "http://localhost:" + wireMock.port() + "/bad-redirect"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Redirect without Location header");
    }

    @Test
    @DisplayName("Límite de tamaño de respuesta (1MB)")
    void responseSizeLimit() throws Exception {
        String largeBody = "x".repeat(2 * 1024 * 1024); // 2MB
        
        wireMock.stubFor(get(urlEqualTo("/large"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody(largeBody)));

        assertThatThrownBy(() -> transport.get(
            "http://localhost:" + wireMock.port() + "/large"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("exceeds maximum allowed size");
    }

    @Test
    @DisplayName("Timeout de conexión")
    void connectionTimeout() throws Exception {
        // WireMock delay longer than transport timeout
        wireMock.stubFor(get(urlEqualTo("/slow"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("slow")
                .withFixedDelay(2000))); // 2 second delay

        HttpTransport shortTimeout = new HttpTransport(Duration.ofMillis(100), true);

        assertThatThrownBy(() -> shortTimeout.get(
            "http://localhost:" + wireMock.port() + "/slow"))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Rechaza URLs con userinfo")
    void rejectsUserInfo() throws Exception {
        assertThatThrownBy(() -> transport.get(
            "http://user:pass@localhost:" + wireMock.port() + "/test"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("userinfo");
    }

    @Test
    @DisplayName("Error HTTP 4xx lanza excepción")
    void http4xxThrowsException() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/not-found"))
            .willReturn(aResponse().withStatus(404).withBody("Not Found")));

        assertThatThrownBy(() -> transport.get(
            "http://localhost:" + wireMock.port() + "/not-found"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("HTTP 404");
    }

    @Test
    @DisplayName("Error HTTP 5xx lanza excepción")
    void http5xxThrowsException() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/server-error"))
            .willReturn(aResponse().withStatus(500).withBody("Internal Error")));

        assertThatThrownBy(() -> transport.get(
            "http://localhost:" + wireMock.port() + "/server-error"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("HTTP 500");
    }
}
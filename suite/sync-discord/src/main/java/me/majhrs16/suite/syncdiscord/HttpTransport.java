package me.majhrs16.suite.syncdiscord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** {@link Transport} backed by the JDK HTTP client. */
public final class HttpTransport implements Transport {

    private final HttpClient client;
    private final Duration timeout;

    public HttpTransport() {
        this(Duration.ofSeconds(10));
    }

    public HttpTransport(Duration timeout) {
        this.client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.timeout = timeout;
    }

    @Override
    public String post(String url, Map<String, String> headers, String jsonBody) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", "TextFormatterSuite/2.1")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        HttpRequest request = builder.build();
        try {
            HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request interrupted", e);
        }
    }
}
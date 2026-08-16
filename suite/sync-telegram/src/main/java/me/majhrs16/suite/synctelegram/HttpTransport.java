package me.majhrs16.suite.synctelegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link Transport} backed by the JDK HTTP client with query-string support
 * (Telegram's Bot API takes parameters in the URL).
 */
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
    public String get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", "TextFormatterSuite/2.1")
            .GET()
            .build();
        return send(request);
    }

    @Override
    public String post(String url, String jsonBody) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("User-Agent", "TextFormatterSuite/2.1")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        return send(request);
    }

    /** Builds a URL with percent-encoded query parameters. */
    public static String withQuery(String base, Map<String, String> params) {
        StringBuilder builder = new StringBuilder(base);
        char sep = '?';
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            builder.append(sep)
                .append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            sep = '&';
        }
        return builder.toString();
    }

    private String send(HttpRequest request) throws IOException {
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

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
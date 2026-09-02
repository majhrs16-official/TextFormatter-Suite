package me.majhrs16.suite.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Production {@link Transport} backed by {@link HttpURLConnection}.
 * <p>
 * Avoids {@code java.net.http.HttpClient} module accessibility issues
 * in plugin classloaders (Paper/Spigot).
 */
public final class HttpTransport implements Transport {

    private final Duration timeout;

    public HttpTransport() {
        this(Duration.ofSeconds(10));
    }

    public HttpTransport(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public String get(String url) throws IOException {
        HttpURLConnection conn = openConnection(url, "GET", null, null);
        return readResponse(conn);
    }

    @Override
    public String post(String url, String jsonBody) throws IOException {
        HttpURLConnection conn = openConnection(url, "POST", Map.of("Content-Type", "application/json"), jsonBody);
        return readResponse(conn);
    }

    @Override
    public String post(String url, Map<String, String> headers, String jsonBody) throws IOException {
        HttpURLConnection conn = openConnection(url, "POST", headers, jsonBody);
        return readResponse(conn);
    }

    private HttpURLConnection openConnection(String urlString, String method, Map<String, String> headers, String body) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout((int) timeout.toMillis());
        conn.setReadTimeout((int) timeout.toMillis());
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "TextFormatterSuite/2.1");

        if (headers != null) {
            headers.forEach(conn::setRequestProperty);
        }

        if (body != null) {
            conn.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
        }
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                status >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String response = sb.toString();
            if (status >= 400) {
                throw new IOException("HTTP " + status + ": " + response);
            }
            return response;
        } finally {
            conn.disconnect();
        }
    }
}
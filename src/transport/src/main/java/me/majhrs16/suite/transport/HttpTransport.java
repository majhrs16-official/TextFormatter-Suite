package me.majhrs16.suite.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Production {@link Transport} backed by {@link HttpURLConnection}.
 * <p>
 * Avoids {@code java.net.http.HttpClient} module accessibility issues
 * in plugin classloaders (Paper/Spigot).
 * <p>
 * SSRF Protection: Validates URLs against private/internal address ranges
 * before connecting. Configured with a deny-list of RFC 1918, RFC 3927,
 * RFC 6598 and loopback addresses. Custom allow/deny lists can be added
 * via system properties.
 * </p>
 */
public final class HttpTransport implements Transport {

    private static final List<String> DEFAULT_DENY_PATTERNS = List.of(
        // Loopback
        "^127\\.",
        "^::1$",
        "^0:0:0:0:0:0:0:1$",
        // RFC 1918 Private networks
        "^10\\.",
        "^172\\.(1[6-9]|2[0-9]|3[0-1])\\.",
        "^192\\.168\\.",
        // RFC 3927 Link-local
        "^169\\.254\\.",
        // RFC 6598 Carrier-grade NAT
        "^100\\.(6[4-9]|[7-9][0-9]|1[0-1][0-9]|12[0-7])\\.",
        // Multicast
        "^22[4-9]\\.",
        "^23[0-9]\\.",
        // Reserved
        "^0\\."
    );

    private static final Pattern[] DENY_PATTERNS;

    static {
        String denyProp = System.getProperty("textformattersuite.http.deny");
        List<String> patterns = DEFAULT_DENY_PATTERNS;
        if (denyProp != null && !denyProp.isBlank()) {
            patterns = List.of(denyProp.split(","));
        }
        DENY_PATTERNS = patterns.stream()
            .map(Pattern::compile)
            .toArray(Pattern[]::new);
    }

    private final Duration timeout;
    private final boolean ssrfProtectionEnabled;

    public HttpTransport() {
        this(Duration.ofSeconds(10));
    }

    public HttpTransport(Duration timeout) {
        this(timeout, true);
    }

    public HttpTransport(Duration timeout, boolean ssrfProtectionEnabled) {
        this.timeout = timeout;
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
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

        // SSRF Protection: validate host against deny patterns
        if (ssrfProtectionEnabled) {
            validateUrl(url);
        }

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

    /**
     * Validates the URL against SSRF deny patterns.
     * Resolves the host to IP and checks against private/internal ranges.
     */
    private void validateUrl(URL url) throws IOException {
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("Invalid URL: no host");
        }

        // Check if host is an IP address
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (Exception e) {
            throw new IOException("Failed to resolve host: " + host, e);
        }

        String ip = address.getHostAddress();

        // Check against deny patterns
        for (Pattern pattern : DENY_PATTERNS) {
            if (pattern.matcher(ip).matches()) {
                throw new IOException("SSRF blocked: destination " + ip + " matches deny pattern " + pattern.pattern());
            }
        }

        // Also check if it's a loopback or site-local
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            throw new IOException("SSRF blocked: destination " + ip + " is private/internal address");
        }
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
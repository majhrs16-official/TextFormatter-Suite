package me.majhrs16.suite.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
        // IPv6 ULA (fc00::/7)
        "^fc[0-9a-f]:",
        "^fd[0-9a-f]:",
        // Multicast
        "^22[4-9]\\.",
        "^23[0-9]\\.",
        // Reserved
        "^0\\."
    );

    private static final Pattern[] DENY_PATTERNS;
    private static final int MAX_RESPONSE_SIZE = 1024 * 1024; // 1MB

    static {
        String denyProp = System.getProperty("textformattersuite.http.deny");
        List<String> patterns = new ArrayList<>(DEFAULT_DENY_PATTERNS);
        if (denyProp != null && !denyProp.isBlank()) {
            // Append custom patterns to defaults (amplify, not replace)
            patterns.addAll(List.of(denyProp.split(",")));
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

        // SSRF Protection: validate initial URL
        if (ssrfProtectionEnabled) {
            validateUrl(url);
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout((int) timeout.toMillis());
        conn.setReadTimeout((int) timeout.toMillis());
        // Disable automatic redirects - we handle them manually with validation
        conn.setInstanceFollowRedirects(false);
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
     * Resolves the host to all IPs and checks against private/internal ranges.
     * Uses getAllByName to prevent DNS rebinding attacks where a hostname
     * resolves to both public and private IPs.
     */
    private void validateUrl(URL url) throws IOException {
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("Invalid URL: no host");
        }

        // Check if host is an IP address
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new IOException("Failed to resolve host: " + host, e);
        }

        if (addresses.length == 0) {
            throw new IOException("Failed to resolve host: " + host);
        }

        // Check ALL resolved addresses against deny patterns
        for (InetAddress address : addresses) {
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
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        int redirectCount = 0;
        final int MAX_REDIRECTS = 5;
        String requestMethod = conn.getRequestMethod();
        
        while (true) {
            int status = conn.getResponseCode();
            
            // Handle redirects manually with validation
            if (isRedirect(status)) {
                if (redirectCount >= MAX_REDIRECTS) {
                    conn.disconnect();
                    throw new IOException("Too many redirects (" + MAX_REDIRECTS + ")");
                }
                
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                
                if (location == null || location.isBlank()) {
                    throw new IOException("Redirect without Location header");
                }
                
                // Resolve relative URLs
                URL newUrl = new URL(conn.getURL(), location);
                
                // Validate redirect URL
                if (ssrfProtectionEnabled) {
                    validateUrl(newUrl);
                }
                
                // Create new connection for redirect
                conn = (HttpURLConnection) newUrl.openConnection();
                
                // Preserve method for 307/308, use GET for 301/302/303
                if (status == 307 || status == 308) {
                    conn.setRequestMethod(requestMethod);
                } else {
                    conn.setRequestMethod("GET");
                }
                conn.setConnectTimeout((int) timeout.toMillis());
                conn.setReadTimeout((int) timeout.toMillis());
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "TextFormatterSuite/2.1");
                
                redirectCount++;
                continue;
            }
            
            // Not a redirect - read response normally
            InputStream inputStream;
            if (status >= 400) {
                inputStream = conn.getErrorStream();
                if (inputStream == null) {
                    inputStream = conn.getInputStream(); // fallback
                }
            } else {
                inputStream = conn.getInputStream();
            }
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[8192];
                int charsRead;
                int totalChars = 0;
                while ((charsRead = reader.read(buffer)) != -1) {
                    totalChars += charsRead;
                    if (totalChars > MAX_RESPONSE_SIZE) {
                        throw new IOException("Response body exceeds maximum allowed size: " + MAX_RESPONSE_SIZE + " chars");
                    }
                    sb.append(buffer, 0, charsRead);
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
    
    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }
}
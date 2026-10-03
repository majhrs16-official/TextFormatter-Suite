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
        return executeRequest(url, "GET", null, null);
    }

    @Override
    public String post(String url, String jsonBody) throws IOException {
        return executeRequest(url, "POST", Map.of("Content-Type", "application/json"), jsonBody);
    }

    @Override
    public String post(String url, Map<String, String> headers, String jsonBody) throws IOException {
        return executeRequest(url, "POST", headers, jsonBody);
    }

    /**
     * Executes an HTTP request with manual redirect handling that preserves
     * method, headers, and body for 307/308 redirects.
     */
    private String executeRequest(String urlString, String method, Map<String, String> headers, String body) throws IOException {
        // Cache request data for potential redirect reuse
        RequestState state = new RequestState(method, headers, body);
        
        HttpURLConnection conn = createConnection(urlString, state);
        
        return readResponseWithRedirects(conn, state);
    }

    private HttpURLConnection createConnection(String urlString, RequestState state) throws IOException {
        URL url = new URL(urlString);

        // SSRF Protection: validate initial URL and get pinned address
        InetAddress pinnedAddress = null;
        if (ssrfProtectionEnabled) {
            pinnedAddress = validateUrlAndGetAddress(url);
        }

        // Create connection using pinned IP address to prevent TOCTOU DNS rebinding
        String connectionUrl;
        if (pinnedAddress != null) {
            // Replace hostname with pinned IP in URL
            connectionUrl = urlString.replaceFirst(url.getHost(), pinnedAddress.getHostAddress());
        } else {
            connectionUrl = urlString;
        }

        URL connectUrl = new URL(connectionUrl);
        HttpURLConnection conn = (HttpURLConnection) connectUrl.openConnection();
        conn.setRequestMethod(state.method);
        conn.setConnectTimeout((int) timeout.toMillis());
        conn.setReadTimeout((int) timeout.toMillis());
        // Disable automatic redirects - we handle them manually with validation
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "TextFormatterSuite/2.1");

        if (state.headers != null) {
            state.headers.forEach(conn::setRequestProperty);
        }

        if (state.body != null) {
            conn.setDoOutput(true);
            byte[] bytes = state.body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
        }
        return conn;
    }

    private String readResponseWithRedirects(HttpURLConnection conn, RequestState state) throws IOException {
        int redirectCount = 0;
        final int MAX_REDIRECTS = 5;
        
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
                
                // Validate redirect URL using pinned IP
                if (ssrfProtectionEnabled) {
                    validateUrlAndGetAddress(newUrl);
                }
                
                // Create new connection for redirect
                conn = createConnection(newUrl.toString(), state);
                
                // For 307/308, preserve the original method (already done in createConnection)
                // For 301/302/303, createConnection will use the original method but we need GET
                if (status != 307 && status != 308) {
                    // Override to GET for non-307/308 redirects
                    conn.setRequestMethod("GET");
                    // Remove body for GET
                    conn.setDoOutput(false);
                }
                
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
    
    /**
     * Holds the request state for redirect handling.
     * Allows re-creating the request on redirect while preserving method, headers, and body.
     */
    private static final class RequestState {
        final String method;
        final Map<String, String> headers;
        final String body;
        
        RequestState(String method, Map<String, String> headers, String body) {
            this.method = method;
            this.headers = headers;
            this.body = body;
        }
    }

    /**
     * Validates the URL against SSRF deny patterns.
     * Resolves the host to all IPs and checks against private/internal ranges.
     * Returns the first valid IP address to use for the connection (pinning).
     *
     * @return the first valid InetAddress to use for connection, or null if URL uses IP directly
     */
    private InetAddress validateUrlAndGetAddress(URL url) throws IOException {
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("Invalid URL: no host");
        }

        // Check if host is an IP address literal
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

        // Return first valid address for connection pinning (TOCTOU mitigation)
        // Caller should use this address directly instead of hostname
        return addresses[0];
    }
}
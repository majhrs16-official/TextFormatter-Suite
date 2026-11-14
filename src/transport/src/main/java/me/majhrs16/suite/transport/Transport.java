package me.majhrs16.suite.transport;

import java.io.IOException;
import java.util.Map;

/**
 * Unified HTTP transport seam so backends are testable without network.
 * The production implementation uses {@link java.net.HttpURLConnection} for
 * GET, POST, PUT and DELETE, and the JDK {@link java.net.http.HttpClient} for
 * PATCH, which the former rejects.
 *
 * <p>This interface unifies the previously duplicated variants:
 * - {@code get(url)} + {@code post(url, jsonBody)} for translation APIs (Google, Libre, Telegram)
 * - {@code post(url, headers, jsonBody)} for Discord REST API with custom headers
 */
public interface Transport {

    /**
     * Performs a GET request.
     *
     * @param url the full URL including query parameters
     * @return response body as string
     * @throws IOException on network or HTTP error (status >= 400)
     */
    String get(String url) throws IOException;

    /**
     * Performs a POST request with a JSON body.
     *
     * @param url the full URL
     * @param jsonBody JSON-encoded request body
     * @return response body as string
     * @throws IOException on network or HTTP error (status >= 400)
     */
    String post(String url, String jsonBody) throws IOException;

    /**
     * Performs a POST request with a JSON body and custom headers.
     *
     * @param url the full URL
     * @param headers optional custom headers (may be null)
     * @param jsonBody JSON-encoded request body
     * @return response body as string
     * @throws IOException on network or HTTP error (status >= 400)
     */
    default String post(String url, java.util.Map<String, String> headers, String jsonBody) throws java.io.IOException {
        return post(url, jsonBody);
    }

    /**
     * Performs a PUT request with a JSON body and custom headers.
     *
     * @param url the full URL
     * @param headers optional custom headers (may be null)
     * @param jsonBody JSON-encoded request body; {@code null} for no body
     * @return response body as string
     * @throws IOException on network or HTTP error (status >= 400)
     * @throws UnsupportedOperationException when the implementation has no PUT
     */
    default String put(String url, java.util.Map<String, String> headers, String jsonBody)
            throws java.io.IOException {
        throw new UnsupportedOperationException("PUT is not supported by this transport");
    }

    /**
     * Performs a DELETE request with custom headers.
     *
     * @param url the full URL
     * @param headers optional custom headers (may be null)
     * @return response body as string
     * @throws IOException on network or HTTP error (status >= 400)
     * @throws UnsupportedOperationException when the implementation has no DELETE
     */
    default String delete(String url, java.util.Map<String, String> headers)
            throws java.io.IOException {
        throw new UnsupportedOperationException("DELETE is not supported by this transport");
    }

    /**
     * Performs a PATCH request with a JSON body and custom headers.
     *
     * @param url the full URL
     * @param headers optional custom headers (may be null)
     * @param jsonBody JSON-encoded request body; {@code null} for no body
     * @return response body as string
     * @throws IOException on network or HTTP error (status >= 400)
     * @throws UnsupportedOperationException when the implementation has no PATCH
     */
    default String patch(String url, java.util.Map<String, String> headers, String jsonBody)
            throws java.io.IOException {
        throw new UnsupportedOperationException("PATCH is not supported by this transport");
    }

    /**
     * Builds a URL with percent-encoded query parameters.
     *
     * @param base base URL (without query string)
     * @param params query parameters to append
     * @return URL with encoded query string
     */
    static String withQuery(String base, java.util.Map<String, String> params) {
        StringBuilder builder = new StringBuilder(base);
        char sep = '?';
        for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            builder.append(sep)
                .append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            sep = '&';
        }
        return builder.toString();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}

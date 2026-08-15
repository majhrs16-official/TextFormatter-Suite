package me.majhrs16.cht.core.translate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal synchronous HTTP client used by the translation backends.
 *
 * <p>Kept intentionally small and dependency-free: opens a connection, writes
 * an optional form body and reads the response fully. All failures surface as
 * {@link IOException}.</p>
 */
final class Http {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private Http() {
    }

    /**
     * Executes a GET request and returns the response body as a UTF-8 string.
     *
     * @param url     the full request URL.
     * @param headers extra request headers.
     * @return the response body.
     */
    static String get(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = open(url, "GET");
        headers.forEach(connection::setRequestProperty);
        try (InputStream stream = connection.getInputStream()) {
            return readAll(stream);
        } catch (IOException first) {
            throw withResponseBody(connection, first);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Executes a POST request with a form-encoded body.
     */
    static String post(String url, Map<String, String> form, Map<String, String> headers)
            throws IOException {
        return post(url, encodeForm(form), "application/x-www-form-urlencoded; charset=UTF-8",
            headers);
    }

    /**
     * Executes a POST request with a raw text body (e.g. JSON).
     */
    static String post(String url, String body, String contentType, Map<String, String> headers)
            throws IOException {
        HttpURLConnection connection = open(url, "POST");
        headers.forEach(connection::setRequestProperty);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("Accept", "application/json");
        try (OutputStream output = connection.getOutputStream();
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writer.write(body);
        }
        try (InputStream stream = connection.getInputStream()) {
            return readAll(stream);
        } catch (IOException first) {
            throw withResponseBody(connection, first);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent",
            "ChatTranslator/3.0 (Minecraft plugin)");
        return connection;
    }

    private static String readAll(InputStream input) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                body.append(buffer, 0, read);
            }
        }
        return body.toString();
    }

    private static IOException withResponseBody(HttpURLConnection connection, IOException first) {
        try (InputStream error = connection.getErrorStream()) {
            if (error == null) {
                return first;
            }
            return new IOException("HTTP " + connection.getResponseCode() + ": "
                + readAll(error), first);
        } catch (IOException suppressed) {
            first.addSuppressed(suppressed);
            return first;
        }
    }

    private static String encodeForm(Map<String, String> form) throws IOException {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (encoded.length() > 0) {
                encoded.append('&');
            }
            encoded.append(encode(entry.getKey()))
                .append('=')
                .append(encode(entry.getValue()));
        }
        return encoded.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
package me.majhrs16.suite.transport;

import java.io.IOException;

/**
 * HTTP failure with the status code and response body kept as data.
 *
 * <p>Backends need the status code to map a platform failure to their own
 * result type (for example {@code 404 -> NotFound}). It extends
 * {@link IOException}, so every existing caller that treats transport failures
 * as I/O errors keeps working unchanged.</p>
 */
public final class TransportException extends IOException {

    private final int statusCode;
    private final String body;

    public TransportException(int statusCode, String body) {
        super("HTTP " + statusCode + ": " + (body == null ? "" : body));
        if (statusCode < 400) {
            throw new IllegalArgumentException("statusCode must be an HTTP error: " + statusCode);
        }
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
    }

    /** @return the HTTP status code (always {@code >= 400}) */
    public int statusCode() {
        return statusCode;
    }

    /** @return the response body, never {@code null} */
    public String body() {
        return body;
    }
}

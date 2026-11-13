package me.majhrs16.suite.synctelegram;

import me.majhrs16.suite.transport.Transport;
import me.majhrs16.suite.transport.TransportException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal Telegram Bot API client over the neutral {@link Transport}.
 *
 * <p>The bot token lives in a {@code char[]} that is zeroed by {@link #close()}
 * (ADR-003 §4); it is only materialised into the request URL for the duration
 * of the call. An API-level error never escapes as an exception: the parsed
 * JSON body is returned so the sink maps {@code error_code} to an explicit
 * {@link me.majhrs16.suite.api.sync.SyncResult}.</p>
 */
final class TelegramApi {

    private static final String BASE_URL = "https://api.telegram.org/bot";

    private final char[] token;
    private final Transport transport;
    private boolean closed;

    TelegramApi(String token, Transport transport) {
        String value = Objects.requireNonNull(token, "token");
        if (value.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        this.token = value.toCharArray();
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * Calls a Bot API method with a JSON body.
     *
     * @param method Bot API method name, e.g. {@code deleteMessage}
     * @param params request body
     * @return the parsed response, including {@code ok=false} bodies
     * @throws IOException on network failure, invalid JSON or a stopped sink
     */
    JSONObject post(String method, JSONObject params) throws IOException {
        Objects.requireNonNull(params, "params");
        return execute(() -> transport.post(url(method), params.toString()));
    }

    /**
     * Calls a Bot API method with query parameters.
     *
     * @param method Bot API method name, e.g. {@code getUpdates}
     * @param params query parameters
     * @return the parsed response, including {@code ok=false} bodies
     * @throws IOException on network failure, invalid JSON or a stopped sink
     */
    JSONObject get(String method, Map<String, String> params) throws IOException {
        Objects.requireNonNull(params, "params");
        return execute(() -> transport.get(Transport.withQuery(url(method), params)));
    }

    /** Zeroes the token buffer; every later call fails explicitly. */
    synchronized void close() {
        closed = true;
        Arrays.fill(token, '\0');
    }

    private JSONObject execute(Request request) throws IOException {
        String body;
        try {
            body = request.send();
        } catch (TransportException failure) {
            return errorBody(failure.statusCode(), failure.body());
        }
        try {
            return new JSONObject(body);
        } catch (JSONException malformed) {
            throw new IOException("telegram returned invalid JSON", malformed);
        }
    }

    private synchronized String url(String method) throws IOException {
        if (closed) {
            throw new IOException("telegram sink is stopped");
        }
        return BASE_URL + new String(token) + '/' + method;
    }

    private static JSONObject errorBody(int status, String body) {
        try {
            JSONObject parsed = new JSONObject(body);
            if (!parsed.has("ok")) {
                parsed.put("ok", false);
            }
            if (!parsed.has("error_code")) {
                parsed.put("error_code", status);
            }
            return parsed;
        } catch (JSONException notJson) {
            return new JSONObject()
                .put("ok", false)
                .put("error_code", status)
                .put("description", body == null || body.isBlank()
                    ? "HTTP " + status : body);
        }
    }

    @FunctionalInterface
    private interface Request {
        String send() throws IOException;
    }
}

package me.majhrs16.suite.ltranslate;

import me.majhrs16.suite.api.spi.TranslationException;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.transport.Transport;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Objects;

/**
 * LibreTranslate backend (self-hosted or public instance) using its REST API.
 *
 * <p>Language codes are normalized to the variant set LibreTranslate speaks:
 * {@code zh-CN}/{@code zh-TW} collapse into {@code zh} and {@code pt-BR} into
 * {@code pt}, mirroring the provider's langmap.</p>
 */
public final class LTranslate implements Translator {

    private final String baseUrl;
    private final String apiKey;
    private final Transport transport;

    public LTranslate(String baseUrl, Transport transport) {
        this(baseUrl, null, transport);
    }

    public LTranslate(String baseUrl, String apiKey, Transport transport) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.apiKey = apiKey;
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public String name() {
        return "libre";
    }

    @Override
    public String translate(String text, String from, String to) throws TranslationException {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        JSONObject payload = new JSONObject()
            .put("q", text)
            .put("source", from == null || from.equals("auto") ? "auto" : from)
            .put("target", normalize(to))
            .put("format", "text");
        if (apiKey != null && !apiKey.isBlank()) {
            payload.put("api_key", apiKey);
        }
        try {
            String body = transport.post(baseUrl + "/translate", payload.toString());
            String translated = new JSONObject(body).optString("translatedText", "");
            return translated.isEmpty() ? text : translated;
        } catch (Exception e) {
            throw new TranslationException("libretranslate translate call failed", e);
        }
    }

    @Override
    public String detect(String text) {
        if (text == null || text.isEmpty()) {
            return "en";
        }
        JSONObject payload = new JSONObject().put("q", text);
        if (apiKey != null && !apiKey.isBlank()) {
            payload.put("api_key", apiKey);
        }
        try {
            JSONArray results = new JSONArray(transport.post(baseUrl + "/detect", payload.toString()));
            String detected = results.optJSONObject(0).optString("language", "");
            return detected.isEmpty() ? "en" : detected;
        } catch (Exception e) {
            return "en";
        }
    }

    @Override
    public boolean isAvailable() {
        return transport != null && baseUrl != null;
    }

    /** Collapses provider unsupported dialects into the base LTranslate code. */
    static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        if (code.equals("zh-CN") || code.equals("zh-TW") || code.startsWith("zh-")) {
            return "zh";
        }
        if (code.startsWith("pt-")) {
            return "pt";
        }
        return code;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

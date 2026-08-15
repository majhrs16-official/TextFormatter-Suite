package me.majhrs16.cht.core.translate;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.util.Collections;

/**
 * Client for a self-hosted <a href="https://libretranslate.com">LibreTranslate</a>
 * instance. The provider must expose the standard {@code POST /translate}
 * JSON API.
 */
public final class LibreTranslator implements Translator {

    private static final String TRANSLATE_PATH = "/translate";

    private final JSONParser parser = new JSONParser();
    private final String baseUrl;
    private final String apiKey;

    /**
     * @param baseUrl base URL of the instance, e.g. {@code http://localhost:5000}.
     * @param apiKey  optional API key, or {@code null}/{@code ""} when not required.
     */
    public LibreTranslator(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    @Override
    public String name() {
        return "libre";
    }

    @Override
    public boolean isAvailable() {
        return baseUrl != null && !baseUrl.isEmpty();
    }

    @Override
    public String translate(String text, String from, String to) throws TranslationException {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        JSONObject request = new JSONObject();
        request.put("q", text);
        request.put("source", from);
        request.put("target", to);
        request.put("format", "text");

        try {
            String body = Http.post(baseUrl + TRANSLATE_PATH, request.toJSONString(),
                "application/json; charset=UTF-8", Collections.emptyMap());
            Object parsed = parser.parse(body);
            if (parsed instanceof JSONObject) {
                Object translated = ((JSONObject) parsed).get("translatedText");
                if (translated instanceof String) {
                    return (String) translated;
                }
            }
            throw new TranslationException("LibreTranslate reply without translatedText");
        } catch (IOException | ParseException e) {
            throw new TranslationException("LibreTranslate request failed", e);
        }
    }

    @Override
    public String detect(String text) {
        // LibreTranslate does not expose detection on every instance; fall back
        // to a conservative default instead of failing the whole pipeline.
        return "en";
    }
}
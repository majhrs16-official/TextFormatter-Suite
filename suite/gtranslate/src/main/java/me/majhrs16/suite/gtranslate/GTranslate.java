package me.majhrs16.suite.gtranslate;

import me.majhrs16.suite.api.spi.TranslationException;
import me.majhrs16.suite.api.spi.Translator;

import org.json.JSONArray;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Google Translate backend via the free {@code translate_a/single} endpoint
 * ({@code client=gtx}), which requires no API key.
 *
 * <p>Language codes follow {@code Language} codes directly (e.g.
 * {@code zh-CN}, {@code es}). Detection is delegated to the same call with
 * {@code sl=auto}; the reply exposes the detected source code.</p>
 */
public final class GTranslate implements Translator {

    private static final String BASE =
        "https://translate.googleapis.com/translate_a/single";

    private final Transport transport;

    public GTranslate(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public String name() {
        return "google";
    }

    @Override
    public String translate(String text, String from, String to) throws TranslationException {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String source = (from == null || from.equals("auto")) ? "auto" : from;
        String url = BASE
            + "?client=gtx&dt=t&sl=" + encode(source) + "&tl=" + encode(to) + "&q=" + encode(text);
        try {
            String body = transport.get(url);
            JSONArray root = new JSONArray(body);
            return root.optJSONArray(0).optJSONArray(0).optString(0, text);
        } catch (Exception e) {
            throw new TranslationException("google translate call failed", e);
        }
    }

    @Override
    public String detect(String text) {
        if (text == null || text.isEmpty()) {
            return "en";
        }
        String url = BASE + "?client=gtx&dt=t&sl=auto&tl=en&q=" + encode(text);
        try {
            JSONArray root = new JSONArray(transport.get(url));
            String detected = root.optString(2, "");
            return detected.isEmpty() ? "en" : detected;
        } catch (Exception e) {
            return "en";
        }
    }

    @Override
    public boolean isAvailable() {
        return transport != null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
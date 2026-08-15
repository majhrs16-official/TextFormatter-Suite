package me.majhrs16.cht.core.translate;

import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free Google Translate endpoint ({@code client=gtx}), used without an API key.
 *
 * <p>Faithful port of the original closed-library {@code GoogleTranslator}:
 * the text is {@link #encoder(String) encoded} so special characters survive
 * the round trip ({@code "  "} becomes {@code [20][20]}, every character of the
 * {@link #CHARSET} becomes its {@code [%02X]} form), and {@link #decoder}
 * restores them, including broken {@code & (x)} color codes. This preserves
 * color codes and formatting across translation, which a naive URLEncode
 * round-trip loses.</p>
 *
 * <p>Errors follow the original error contracts: {@code [ERR002]/[ERR003]} for
 * URL/encoding problems, {@code [!] } for I/O failures and {@code [ERR000]} for
 * anything else. There is intentionally no retry or backoff -- matching the
 * original behaviour.</p>
 */
public final class GoogleTranslator implements Translator {

    private static final String ENDPOINT =
        "https://translate.googleapis.com/translate_a/single";

    /** Characters that get hex-escaped, exactly as the original lib. */
    public static final char[] CHARSET = new char[] {
        '%', '#', '%', '&', '?', '=', '/', '.', ':', '+', ';', '>', '<', '!', '\t', '\n',
    };

    private static final Pattern COLOR_CODES = Pattern.compile("& (.)");

    private final JSONParser parser = new JSONParser();
    private final boolean enabled;

    public GoogleTranslator() {
        this(true);
    }

    public GoogleTranslator(boolean enabled) {
        this.enabled = enabled;
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
        String url = "NULL";
        String json = "NULL";
        try {
            url = ENDPOINT + "?client=gtx&sl=" + from + "&tl=" + to
                + "&dt=t&q=" + encoder(text);
            json = Http.get(url, Collections.emptyMap());
            JSONArray parsed = (JSONArray) parser.parse(json);
            parsed = (JSONArray) parsed.get(0);
            parsed = (JSONArray) parsed.get(0);
            return decoder((String) parsed.get(0));
        } catch (UnsupportedEncodingException e) {
            return "[ERR003] " + text;
        } catch (java.net.MalformedURLException e) {
            return "[ERR002] " + text;
        } catch (IOException e) {
            return "[!] " + text;
        } catch (ParseException | RuntimeException e) {
            return "[ERR000] " + text;
        }
    }

    @Override
    public String detect(String text) {
        if (text == null || text.isEmpty()) {
            return "en";
        }
        Map<String, String> query = new HashMap<>();
        query.put("client", "gtx");
        query.put("sl", "auto");
        query.put("tl", "en");
        query.put("dt", "t");
        query.put("q", text);

        String body;
        try {
            body = Http.get(ENDPOINT + "?" + queryString(query), Collections.emptyMap());
        } catch (IOException ignored) {
            return "en";
        }
        JSONArray root;
        try {
            Object parsed = parser.parse(body);
            root = parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray();
        } catch (ParseException ignored) {
            return "en";
        }
        if (root != null && root.size() > 2) {
            Object detected = root.get(2);
            if (detected instanceof String && !((String) detected).isEmpty()) {
                return (String) detected;
            }
        }
        return "en";
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    /**
     * Encodes a text so special characters survive the translation round trip,
     * exactly as the original lib does: {@code "  "} becomes {@code [20][20]}
     * and every charset character becomes {@code [%02X]}.
     */
    public String encoder(String text) throws UnsupportedEncodingException {
        text = text.replace("  ", "[20][20]");
        for (char c : CHARSET) {
            text = text.replace(String.valueOf(c), String.format("[%02X]", (int) c));
        }
        return URLEncoder.encode(text, StandardCharsets.UTF_8.toString());
    }

    /**
     * Decodes a translated text back, restoring escaped characters and color
     * codes. A trailing space restoration handles Google's reformatting.
     */
    public String decoder(String text) throws UnsupportedEncodingException {
        text = text.replace("[ ", "[");
        text = text.replace(" ]", "]");
        for (char c : CHARSET) {
            text = text.replace(String.format("[%02X]", (int) c), String.valueOf(c));
        }
        text = text.replace("[20]", " ");
        Matcher match = COLOR_CODES.matcher(text);
        while (match.find()) {
            String code = match.group(1);
            text = text.replace("& " + code, "&" + code);
        }
        return text;
    }

    private static String queryString(Map<String, String> query) {
        StringBuilder url = new StringBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (url.length() > 0) {
                url.append('&');
            }
            url.append(entry.getKey()).append('=');
            try {
                url.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8.name()));
            } catch (UnsupportedEncodingException impossible) {
                url.append(entry.getValue());
            }
        }
        return url.toString();
    }
}
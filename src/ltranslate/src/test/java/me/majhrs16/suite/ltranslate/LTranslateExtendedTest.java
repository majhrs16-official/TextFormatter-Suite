package me.majhrs16.suite.ltranslate;

import me.majhrs16.suite.api.spi.TranslationException;
import me.majhrs16.suite.transport.Transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LTranslateExtendedTest {

    private static final String TRANSLATE_BODY = "{\"translatedText\":\"hola\"}";
    private static final String DETECT_BODY = "[{\"confidence\":0.98,\"language\":\"en\"}]";
    private static final String ERROR_BODY = "{\"error\":{\"code\":500,\"message\":\"Internal Server Error\"}}";

    private static LTranslate makeTranslator(String postResponse) {
        return new LTranslate("https://example.org", new Transport() {
            @Override public String get(String url) { return ""; }
            @Override public String post(String url, String jsonBody) { return postResponse; }
            @Override public String post(String url, java.util.Map<String, String> headers, String jsonBody) {
                return postResponse;
            }
        });
    }

    @Test
    void extractsTranslatedText() {
        assertEquals("hola", makeTranslator(TRANSLATE_BODY).translate("hello", "en", "es"));
    }

    @Test
    void detectsLanguage() {
        assertEquals("en", makeTranslator(DETECT_BODY).detect("hello"));
    }

    @Test
    void leaveEmptyTextUntouched() {
        assertEquals("", makeTranslator(TRANSLATE_BODY).translate("", "en", "es"));
    }

    @Test
    void propagatesBackendFailure() {
        LTranslate libre = makeTranslator(ERROR_BODY);
        // Returns original text on error
        assertEquals("hello", libre.translate("hello", "en", "es"));
    }

    @Test
    void normalizesProviderDialects() {
        assertEquals("zh", LTranslate.normalize("zh-CN"));
        assertEquals("zh", LTranslate.normalize("zh-TW"));
        assertEquals("pt", LTranslate.normalize("pt-BR"));
        assertEquals("es", LTranslate.normalize("es"));
        assertEquals("en-gb", LTranslate.normalize("en-gb"));
    }

    @Test
    void sendsApiKeyWhenConfigured() {
        String[] body = new String[1];
        LTranslate libre = new LTranslate("https://example.org", "secret-key", new Transport() {
            @Override public String get(String url) { return ""; }
            @Override public String post(String url, String jsonBody) {
                body[0] = jsonBody;
                return TRANSLATE_BODY;
            }
            @Override public String post(String url, java.util.Map<String, String> headers, String jsonBody) { return post(url, jsonBody); }
        });

        libre.translate("hello", "en", "es");
        assertTrue(body[0].contains("\"api_key\":\"secret-key\""));
    }

    @Test
    void handlesMalformedJsonResponse() {
        LTranslate libre = makeTranslator("not valid json");
        assertThrows(TranslationException.class, () -> libre.translate("hello", "en", "es"));
    }

    @Test
    void handlesMissingTranslatedTextField() {
        String response = "{\"otherField\":\"value\"}";
        LTranslate libre = makeTranslator(response);
        // Returns original text when translatedText is missing/empty
        assertEquals("hello", libre.translate("hello", "en", "es"));
    }

    @Test
    void handlesEmptyDetectResponse() {
        String response = "[]";
        LTranslate libre = makeTranslator(response);
        // LibreTranslate returns "en" for empty detect response
        assertEquals("en", libre.detect("hello"));
    }
@Test
    void handlesTransportException() {
        LTranslate libre = new LTranslate("https://example.org", new Transport() {
            @Override public String get(String url) { return ""; }
            @Override public String post(String url, String jsonBody) throws java.io.IOException {
                throw new java.io.IOException("connection refused");
            }
            @Override
            public String post(String url, java.util.Map<String, String> headers, String jsonBody) throws java.io.IOException {
                throw new java.io.IOException("connection refused");
            }
        });

        assertThrows(TranslationException.class, () -> libre.translate("hello", "en", "es"));
    }

    @Test
    void nameReturnsLibre() {
        assertEquals("libre", makeTranslator(TRANSLATE_BODY).name());
    }

    @Test
    void translatePreservesWhitespace() {
        String response = "{\"translatedText\":\"  hola  mundo  \"}";
        String result = makeTranslator(response).translate("  hello  world  ", "en", "es");
        assertEquals("  hola  mundo  ", result);
    }

    @Test
    void handlesUnicodeCharacters() {
        String response = "{\"translatedText\":\"こんにちは世界\"}";
        String result = makeTranslator(response).translate("hello world", "en", "ja");
        assertEquals("こんにちは世界", result);
    }

    @Test
    void handlesRateLimitResponse() {
        String response = "{\"error\":{\"code\":429,\"message\":\"Too Many Requests\"}}";
        LTranslate libre = makeTranslator(response);
        // Returns original text on error
        assertEquals("hello", libre.translate("hello", "en", "es"));
    }

    @Test
    void isAvailableReturnsTrueWhenConfigured() {
        assertTrue(makeTranslator(TRANSLATE_BODY).isAvailable());
    }

    @Test
    void isAvailableReturnsFalseWhenNoTransport() {
        LTranslate libre = new LTranslate("https://example.org", new Transport() {
            @Override public String get(String url) { return ""; }
            @Override public String post(String url, String jsonBody) { return ""; }
            @Override public String post(String url, java.util.Map<String, String> headers, String jsonBody) { return ""; }
        });
        assertTrue(libre.isAvailable());
    }
}
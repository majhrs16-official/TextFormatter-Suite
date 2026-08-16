package me.majhrs16.suite.ltranslate;

import me.majhrs16.suite.api.spi.TranslationException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LTranslateTest {

    private static final String TRANSLATE_BODY =
        "{\"translatedText\":\"hola\"}";
    private static final String DETECT_BODY =
        "[{\"confidence\":0.98,\"language\":\"en\"}]";

    private static LTranslate translatorUnion() {
        return new LTranslate("https://example.org", new Transport() {
            @Override public String get(String url) { return ""; }
            @Override public String post(String url, String jsonBody) {
                return url.endsWith("/detect") ? DETECT_BODY : TRANSLATE_BODY;
            }
        });
    }

    @Test
    void extractsTranslatedText() {
        assertEquals("hola", translatorUnion().translate("hello", "en", "es"));
    }

    @Test
    void detectsLanguage() {
        assertEquals("en", translatorUnion().detect("hello"));
    }

    @Test
    void leaveEmptyTextUntouched() {
        assertEquals("", translatorUnion().translate("", "en", "es"));
    }

    @Test
    void propagatesBackendFailure() {
        LTranslate libre = new LTranslate("https://example.org", new Transport() {
            @Override public String get(String url) { return ""; }
            @Override public String post(String url, String jsonBody) throws java.io.IOException {
                throw new java.io.IOException("unreachable");
            }
        });

        assertThrows(TranslationException.class, () -> libre.translate("hello", "en", "es"));
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
        });

        libre.translate("hello", "en", "es");
        assertEquals(true, body[0].contains("\"api_key\":\"secret-key\""));
    }
}
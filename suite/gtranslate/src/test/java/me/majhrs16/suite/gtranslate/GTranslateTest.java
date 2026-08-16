package me.majhrs16.suite.gtranslate;

import me.majhrs16.suite.api.spi.TranslationException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GTranslateTest {

    @Test
    void extractsTranslatedTextFromRootArray() throws Exception {
        GTranslate google = new GTranslate(new Transport() {
            @Override public String get(String url) {
                return "[[[\"hola\",\"hello\",null,null,10]],null,\"en\",,,\"GTranslate\"]";
            }
            @Override public String post(String url, String jsonBody) { return ""; }
        });

        assertEquals("hola", google.translate("hello", "en", "es"));
    }

    @Test
    void detectsLanguageFromSecondTuple() {
        GTranslate google = new GTranslate(new Transport() {
            @Override public String get(String url) {
                return "[[[\"hola\",\"hello\",null,null,10]],null,\"es\",,,\"GTranslate\"]";
            }
            @Override public String post(String url, String jsonBody) { return ""; }
        });

        assertEquals("es", google.detect("hola"));
    }

    @Test
    void leaveEmptyTextUntouched() {
        GTranslate google = new GTranslate(new Transport() {
            @Override public String get(String url) { throw new IllegalStateException(); }
            @Override public String post(String url, String jsonBody) { throw new IllegalStateException(); }
        });

        assertEquals("", google.translate("", "en", "es"));
    }

    @Test
    void propagatesTransportFailureAsTranslationException() {
        GTranslate google = new GTranslate(new Transport() {
            @Override public String get(String url) throws java.io.IOException {
                throw new java.io.IOException("network down");
            }
            @Override public String post(String url, String jsonBody) { return ""; }
        });

        assertThrows(TranslationException.class, () -> google.translate("hello", "en", "es"));
    }

    @Test
    void isAvailableWhenTransportConfigured() {
        Transport transport = new Transport() {
            @Override public String get(String url) { return ""; }
            @Override public String post(String url, String jsonBody) { return ""; }
        };
        assertTrue(new GTranslate(transport).isAvailable());
    }
}
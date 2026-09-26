package me.majhrs16.suite.gtranslate;

import me.majhrs16.suite.api.spi.TranslationException;
import me.majhrs16.suite.transport.Transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GTranslateExtendedTest {

    private static Transport stubTransport(String getResponse) {
        return new Transport() {
            @Override public String get(String url) { return getResponse; }
            @Override public String post(String url, String jsonBody) { return ""; }
            @Override public String post(String url, java.util.Map<String, String> headers, String jsonBody) { return post(url, jsonBody); }
        };
    }

    @Test
    void extractsTranslatedTextFromRootArray() throws Exception {
        GTranslate google = new GTranslate(stubTransport("[[[\"hola\",\"hello\",null,null,10]],null,\"en\",,,\"GTranslate\"]"));
        assertEquals("hola", google.translate("hello", "en", "es"));
    }

    @Test
    void handlesMultipleTranslatedSegments() throws Exception {
        // Google returns multiple segments in the array
        String response = "[[[\"hola\",\"hello\",null,null,10],[\"mundo\",\"world\",null,null,11]],null,\"en\",,,\"GTranslate\"]";
        GTranslate google = new GTranslate(stubTransport(response));
        // Should concatenate or return first segment
        String result = google.translate("hello world", "en", "es");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void detectsLanguageFromSecondTuple() {
        GTranslate google = new GTranslate(stubTransport("[[[\"hola\",\"hello\",null,null,10]],null,\"es\",,,\"GTranslate\"]"));
        assertEquals("es", google.detect("hola"));
    }

    @Test
    void leaveEmptyTextUntouched() {
        GTranslate google = new GTranslate(new Transport() {
            @Override public String get(String url) { throw new IllegalStateException(); }
            @Override public String post(String url, String jsonBody) { return ""; }
            @Override public String post(String url, java.util.Map<String, String> headers, String jsonBody) { return ""; }
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
            @Override public String post(String url, java.util.Map<String, String> headers, String jsonBody) { return ""; }
        });
        assertThrows(TranslationException.class, () -> google.translate("hello", "en", "es"));
    }

    @Test
    void isAvailableWhenTransportConfigured() {
        Transport transport = new Transport() {
            @Override public String get(String url) { return ""; }
            @Override public String post(String url, String jsonBody) { return ""; }
            @Override public String post(String url, java.util.Map<String, String> headers, String jsonBody) { return ""; }
        };
        assertTrue(new GTranslate(transport).isAvailable());
    }

    @Test
    void handlesMalformedResponseThrowsException() {
        GTranslate google = new GTranslate(stubTransport("not valid json"));
        assertThrows(TranslationException.class, () -> google.translate("hello", "en", "es"));
    }

    @Test
    void handlesEmptyResponseArrayThrowsException() {
        GTranslate google = new GTranslate(stubTransport("[[],null,\"en\",,,\"GTranslate\"]"));
        assertThrows(TranslationException.class, () -> google.translate("hello", "en", "es"));
    }

    @Test
    void handlesNullInResponseThrowsException() {
        GTranslate google = new GTranslate(stubTransport("[[null,null,null,null,null],null,\"en\",,,\"GTranslate\"]"));
        assertThrows(TranslationException.class, () -> google.translate("hello", "en", "es"));
    }

    @Test
    void nameReturnsGoogle() {
        GTranslate google = new GTranslate(stubTransport("[[[\"hola\",\"hello\",null,null,10]],null,\"en\",,,\"GTranslate\"]"));
        assertEquals("google", google.name());
    }

    @Test
    void translateWithSpecialCharacters() throws Exception {
        GTranslate google = new GTranslate(stubTransport("[[[\"hola & mundo\",\"hello & world\",null,null,10]],null,\"en\",,,\"GTranslate\"]"));
        String result = google.translate("hello & world", "en", "es");
        assertEquals("hola & mundo", result);
    }
}
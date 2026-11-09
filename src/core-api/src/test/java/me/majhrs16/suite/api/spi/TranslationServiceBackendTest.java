package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationServiceBackendTest {

    @Test
    void hasProviderMatchesRegisteredNamesCaseInsensitively() {
        TranslationService service = service(
                new NamedTranslator("active", "A", true),
                new NamedTranslator("gtranslate", "G", true));

        assertTrue(service.hasProvider("gtranslate"));
        assertTrue(service.hasProvider("GTRANSLATE"));
        assertFalse(service.hasProvider("ltranslate"));
        assertFalse(service.hasProvider(null));
        assertFalse(service.hasProvider(" "));
    }

    @Test
    void namedBackendOverridesTheActiveProvider() {
        TranslationService service = service(
                new NamedTranslator("active", "A", true),
                new NamedTranslator("gtranslate", "G", true));

        assertEquals("G:hola", service.translate("hola", Language.EN, Language.ES, "gtranslate"));
        assertEquals("A:hola", service.translate("hola", Language.EN, Language.ES));
    }

    @Test
    void unknownBackendIsAnExplicitError() {
        TranslationService service = service(
                new NamedTranslator("active", "A", true));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.translate("hola", Language.EN, Language.ES, "nope"));
        assertEquals("unknown translation backend 'nope'", failure.getMessage());

        // The backend is validated before the empty-text shortcut, so an
        // unknown provider never becomes a silent no-op.
        assertThrows(IllegalArgumentException.class,
                () -> service.translate("", Language.EN, Language.ES, "nope"));
    }

    @Test
    void unavailableNamedBackendFallsBackToTheActiveProvider() {
        TranslationService service = service(
                new NamedTranslator("active", "A", true),
                new NamedTranslator("gtranslate", "G", false));

        assertEquals("A:hola", service.translate("hola", Language.EN, Language.ES, "gtranslate"));
    }

    @Test
    void cacheKeysAreSeparatedByBackend() {
        TranslationService service = service(
                new NamedTranslator("active", "A", true),
                new NamedTranslator("gtranslate", "G", true));

        service.translate("hola", Language.EN, Language.ES, "gtranslate");
        service.translate("hola", Language.EN, Language.ES);

        assertEquals(2, service.getCacheStats().translationCacheSize());
    }

    private static TranslationService service(NamedTranslator... translators) {
        TranslatorManager manager = new TranslatorManager();
        for (NamedTranslator translator : translators) {
            manager.add(translator);
        }
        return new TranslationService(manager);
    }

    private static final class NamedTranslator implements Translator {
        private final String name;
        private final String prefix;
        private final boolean available;

        private NamedTranslator(String name, String prefix, boolean available) {
            this.name = name;
            this.prefix = prefix;
            this.available = available;
        }

        @Override public String name() { return name; }

        @Override public String translate(String text, String from, String to) {
            return prefix + ":" + text;
        }

        @Override public String detect(String text) { return "en"; }

        @Override public boolean isAvailable() { return available; }
    }
}

package me.majhrs16.suite.host;

import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorProvider;
import me.majhrs16.suite.api.spi.TranslationException;

import java.util.Map;

/**
 * Fake TranslatorProvider for testing purposes.
 * Returns a translator that prefixes the target language code.
 */
public final class FakeTranslatorProvider implements TranslatorProvider {

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public Translator create(Map<String, Object> config) throws TranslationException {
        return new Translator() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public String translate(String text, String from, String to) {
                return to + ": " + text;
            }

            @Override
            public String detect(String text) {
                return "en";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
    }

    @Override
    public boolean validateConfig(Map<String, Object> config) {
        return true;
    }
}
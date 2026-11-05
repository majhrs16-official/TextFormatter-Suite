package me.majhrs16.suite.messages;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesCatalogTest {

    private final MessagesCatalog catalog = MessagesCatalog.getInstance();

    @Test
    void formatsEnglishTemplateWithPositionalArguments() {
        assertEquals(
            "[suite] active: 5 channels, translator 'google'",
            catalog.format(Locale.ENGLISH, "enabled", 5, "google")
        );
    }

    @Test
    void formatsSpanishTemplate() {
        assertEquals(
            "[suite] activo: 3 canales, traductor 'libre'",
            catalog.format(Locale.forLanguageTag("es"), "enabled", 3, "libre")
        );
    }

    @Test
    void returnsKeyWhenMessageDoesNotExist() {
        assertEquals("does.not.exist", catalog.format(Locale.ENGLISH, "does.not.exist"));
        assertEquals("does.not.exist", catalog.getTemplate(Locale.ENGLISH, "does.not.exist"));
    }

    @Test
    void substitutesCurlyPlaceholders() {
        assertEquals("a 1 b 2", catalog.substitute("a {} b {}", 1, 2));
        assertEquals("no arguments", catalog.substitute("no arguments"));
    }
}

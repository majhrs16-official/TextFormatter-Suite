package me.majhrs16.suite.textformatter.template;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRendererTest {

    private static final Actor PLAYER =
        Actor.unknown("Steve").withLanguage(Language.EN);

    private static TemplateRenderer renderer() {
        TranslatorManager managers = new TranslatorManager();
        managers.add(new Translator() {
            @Override public String name() { return "test"; }

            @Override public String translate(String text, String from, String to) {
                return "[" + to + "]" + text;
            }

            @Override public String detect(String text) { return "en"; }

            @Override public boolean isAvailable() { return true; }
        });
        return new TemplateRenderer(
            new TranslationService(managers),
            null,
            logger());
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) { }
            @Override public void warn(String m, Object... a) { }
            @Override public void error(String m, Object... a) { }
            @Override public void error(String m, Throwable t) { }
            @Override public void debug(String m, Object... a) { }
        };
    }

    private static TemplateContext context(String content, Language from, Language to,
                                           boolean translate) {
        return TemplateContext.builder(Actor.unknown("Steve"), from, to)
            .content(content)
            .translate(translate)
            .build();
    }

    @Test
    void rendersBuiltinVariablesUnescapedAsTags() {
        String result = renderer().renderPlain(
            Template.of("<green>%player_name%</green> dice algo"),
            context("algo", Language.EN, Language.ES, false));

        assertTrue(result.contains("Steve"));
        assertFalse(result.contains("green"));
    }

    @Test
    void replacesContentPlaceholders() {
        String result = renderer().renderPlain(
            Template.of("%player_name%: %content%"),
            context("hola", Language.EN, Language.ES, false));

        assertEquals("Steve: hola", result);
    }

    @Test
    void replacesLegacyContentToken() {
        String result = renderer().renderPlain(
            Template.of("%ct_messages%"),
            context("hola", Language.EN, Language.ES, false));

        assertEquals("hola", result);
    }

    @Test
    void legacyTokenInsideTranslatableSpanGetsTranslated() {
        String result = renderer().renderPlain(
            Template.of("<tr>%ct_messages%</tr> mundo"),
            context("hola", Language.EN, Language.ES, true));

        assertEquals("[es]hola mundo", result);
    }

    @Test
    void translatesTranslatableSpans() {
        String result = renderer().renderPlain(
            Template.of("<tr>hola</tr> mundo"),
            context("hola", Language.EN, Language.ES, true));

        assertEquals("[es]hola mundo", result);
    }

    @Test
    void skipsTranslationWhenDisabled() {
        String result = renderer().renderPlain(
            Template.of("<tr>hola</tr>"),
            context("hola", Language.EN, Language.ES, false));

        assertEquals("hola", result);
    }

    @Test
    void skipsTranslationWhenSameLanguage() {
        String result = renderer().renderPlain(
            Template.of("<tr>hola</tr>"),
            context("hola", Language.EN, Language.EN, true));

        assertEquals("hola", result);
    }

    @Test
    void manuallyEscapesUserContent() {
        String result = renderer().renderPlain(
            Template.of("%content%"),
            context("<b>bold</b>", Language.EN, Language.ES, false));

        assertEquals("<b>bold</b>", result);
    }

    @Test
    void resolvesVariables() {
        TemplateContext context = TemplateContext
            .builder(Actor.unknown("Steve"), Language.EN, Language.ES)
            .translate(false)
            .variable("clan", "Lobos")
            .build();

        String result = renderer().renderPlain(
            Template.of("[%clan%] %player_name%"),
            context);

        assertEquals("[Lobos] Steve", result);
    }
}
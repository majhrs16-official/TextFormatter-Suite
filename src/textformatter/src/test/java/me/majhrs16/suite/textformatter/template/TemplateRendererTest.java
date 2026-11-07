package me.majhrs16.suite.textformatter.template;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.PluginLogger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRendererTest {

    private static TemplateRenderer renderer() {
        return new TemplateRenderer(null, logger());
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

    private static TemplateContext context(String content, Language from, Language to) {
        return TemplateContext.builder(Actor.unknown("Steve"), from, to)
            .content(content)
            .build();
    }

    @Test
    void rendersBuiltinVariablesUnescapedAsTags() {
        String result = renderer().renderPlain(
            Template.of("<green>%player_name%</green> dice algo"),
            context("algo", Language.EN, Language.ES));

        assertTrue(result.contains("Steve"));
        assertFalse(result.contains("green"));
    }

    @Test
    void replacesContentPlaceholders() {
        String result = renderer().renderPlain(
            Template.of("%player_name%: %content%"),
            context("hola", Language.EN, Language.ES));

        assertEquals("Steve: hola", result);
    }

    @Test
    void replacesLegacyContentToken() {
        String result = renderer().renderPlain(
            Template.of("%ct_messages%"),
            context("hola", Language.EN, Language.ES));

        assertEquals("hola", result);
    }

    @Test
    void manuallyEscapesUserContent() {
        String result = renderer().renderPlain(
            Template.of("%content%"),
            context("<b>bold</b>", Language.EN, Language.ES));

        assertEquals("<b\\>bold</b\\>", result);
    }

    @Test
    void resolvesVariables() {
        TemplateContext context = TemplateContext
            .builder(Actor.unknown("Steve"), Language.EN, Language.ES)
            .variable("clan", "Lobos")
            .build();

        String result = renderer().renderPlain(
            Template.of("[%clan%] %player_name%"),
            context);

        assertEquals("[Lobos] Steve", result);
    }

    @Test
    void regexSpanRendersMiniMessageContentWhenThePatternMatches() {
        String result = renderer().renderPlain(
            Template.of("<regex match=\"hola\"><red>hola</red></regex>"),
            context("hola", Language.EN, Language.ES));

        assertEquals("hola", result);
    }

    @Test
    void regexSpanEmitsNothingWhenThePatternDoesNotMatch() {
        String result = renderer().renderPlain(
            Template.of("a<regex match=\"adios\"><red>hola</red></regex>b"),
            context("hola", Language.EN, Language.ES));

        assertEquals("ab", result);
    }

    @Test
    void regexSpanUsesFindAndDoesNotInterpolateCaptureGroups() {
        String result = renderer().renderPlain(
            Template.of("<regex match=\"(?i)h(e)llo\">%content%</regex>"),
            context("Hello world", Language.EN, Language.ES));

        assertEquals("Hello world", result);
    }

    @Test
    void regexSpanDecodesXmlEntitiesInThePattern() {
        String result = renderer().renderPlain(
            Template.of("<regex match=\"a&quot;b\">a\"b</regex>"),
            context("", Language.EN, Language.ES));

        assertEquals("a\"b", result);
    }

    @Test
    void malformedXmlEntityFailsWhenTheTemplateIsCreated() {
        assertThrows(IllegalArgumentException.class,
            () -> Template.of("<regex match=\"a&b\">x</regex>"));
        assertThrows(IllegalArgumentException.class,
            () -> Template.of("<regex match=\"&#65;\">x</regex>"));
    }

    @Test
    void invalidRegexPatternFailsWhenTheTemplateIsCreated() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> Template.of("<regex match=\"[\">x</regex>"));

        assertTrue(failure.getMessage().contains("<regex>"));
    }

    @Test
    void missingOrEmptyRegexPatternFailsWhenTheTemplateIsCreated() {
        assertThrows(IllegalArgumentException.class,
            () -> Template.of("<regex>x</regex>"));
        assertThrows(IllegalArgumentException.class,
            () -> Template.of("<regex match=\"\">x</regex>"));
        assertThrows(IllegalArgumentException.class,
            () -> Template.of("<regex match=\"   \">x</regex>"));
    }

    @Test
    void regexPatternAndContentLimitsAreEnforced() {
        String tooLongPattern = "a".repeat(Template.MAX_REGEX_PATTERN_LENGTH + 1);
        assertThrows(IllegalArgumentException.class,
            () -> Template.of("<regex match=\"" + tooLongPattern + "\">x</regex>"));

        String tooLongContent = "a".repeat(Template.MAX_REGEX_CONTENT_LENGTH + 1);
        assertThrows(IllegalArgumentException.class,
            () -> Template.of("<regex match=\".*\">" + tooLongContent + "</regex>"));
    }

    @Test
    void resolvedRegexContentLimitIsEnforcedAtRenderTime() {
        String tooLong = "a".repeat(Template.MAX_REGEX_CONTENT_LENGTH + 1);
        TemplateContext context = TemplateContext
            .builder(Actor.unknown("Steve"), Language.EN, Language.ES)
            .variable("huge", tooLong)
            .build();

        assertThrows(IllegalArgumentException.class, () -> renderer().renderPlain(
            Template.of("<regex match=\".*\">%huge%</regex>"), context));
    }

    @Test
    void nestedRegexSpansAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> Template.of("<regex match=\"x\"><regex match=\"y\">y</regex></regex>"));
    }

    @Test
    void renderingDoesNotMutateTheOriginalTemplate() {
        Template template = Template.of(
            "<regex match=\"hola\"><red>%content%</red></regex>");
        String source = template.source();

        renderer().renderPlain(template, context("hola", Language.EN, Language.ES));

        assertEquals(source, template.source());
    }
}

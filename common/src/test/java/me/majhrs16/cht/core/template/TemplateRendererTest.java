package me.majhrs16.cht.core.template;

import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.platform.PluginLogger;
import me.majhrs16.cht.core.platform.RecordingPlaceholderResolver;
import me.majhrs16.cht.core.platform.SilentLogger;
import me.majhrs16.cht.core.player.Subject;
import me.majhrs16.cht.core.translate.StubTranslator;
import me.majhrs16.cht.core.translate.TranslationService;
import me.majhrs16.cht.core.translate.TranslatorManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRendererTest {

    private TranslationService translation;
    private RecordingPlaceholderResolver placeholders;
    private PluginLogger logger;
    private TemplateRenderer renderer;
    private PlainTextComponentSerializer plainText;

    private final Subject sender =
        new Subject(UUID.randomUUID(), "Majhrs", Subject.SubjectKind.PLAYER, null);

    @BeforeEach
    void setUp() {
        TranslatorManager manager = new TranslatorManager().add(new StubTranslator());
        translation = new TranslationService(manager);
        placeholders = new RecordingPlaceholderResolver(true);
        logger = new SilentLogger();
        renderer = new TemplateRenderer(translation, placeholders, logger);
        plainText = PlainTextComponentSerializer.plainText();
    }

    private String render(String templateSource, TemplateContext context) {
        return plainText.serialize(renderer.render(Template.of(templateSource), context));
    }

    private TemplateContext context(Language target) {
        return TemplateContext
            .builder(sender, Language.ES, target)
            .content("hola mundo")
            .build();
    }

    @Test
    void translatesSpanFromDict() {
        String text = render("<tr>hola mundo</tr>", context(Language.DE));
        assertEquals("hello world", text);
    }

    @Test
    void staticMiniMessageTagsSurviveRendering() {
        TemplateContext ctx = context(Language.EN);
        Component component = renderer.render(
            Template.of("<gray>prefix <tr>hola mundo</tr></gray>"), ctx);
        String text = plainText.serialize(component);
        assertTrue(text.startsWith("prefix "));
        assertTrue(text.contains("hello world"));
    }

    @Test
    void playerInputCannotInjectTags() {
        TemplateContext ctx = TemplateContext
            .builder(sender, Language.ES, Language.ES)
            .content("<red>HACK</red>")
            .build();
        String text = render("<tr>%content%</tr>", ctx);
        // the markup is escaped, so it survives as literal text rather than
        // being interpreted: an unescaped <red> would be consumed as a color
        assertEquals("<red>HACK</red>", text);
    }

    @Test
    void translateDisabledLeavesSpanUntouched() {
        TemplateContext ctx = TemplateContext
            .builder(sender, Language.ES, Language.EN)
            .content("hola mundo")
            .translate(false)
            .build();
        String text = render("<tr>%content%</tr>", ctx);
        assertEquals("hola mundo", text);
    }

    @Test
    void sameLanguageLeavesSpanUntouched() {
        assertEquals("hola mundo", render("<tr>hola mundo</tr>", context(Language.ES)));
    }

    @Test
    void placeholderResultCannotInjectTags() {
        TemplateContext ctx = context(Language.ES);
        String text = render("<tr>%custom_rank%</tr>", ctx);
        // the resolver returns literal "<red>[OWNER]</red>"; it must be escaped
        // so the full literal including angle brackets shows up as text
        assertTrue(text.contains("<red>[OWNER]</red>"));
    }
}

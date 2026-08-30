package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.textformatter.TextFormatters;
import me.majhrs16.suite.textformatter.template.Template;
import me.majhrs16.suite.textformatter.template.TemplateContext;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrossLanguageGoldenTest {

    @TempDir
    Path dir;

    private static final PlainTextComponentSerializer PLAIN =
        PlainTextComponentSerializer.plainText();

    private me.majhrs16.suite.api.spi.PluginLogger quietLogger() {
        return new me.majhrs16.suite.api.spi.PluginLogger() {
            @Override public void info(String m, Object... a) {}
            @Override public void warn(String m, Object... a) {}
            @Override public void error(String m, Object... a) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m, Object... a) {}
        };
    }

    private String getFormat(int i) {
        if (i == 0) return "<gray>%player_name%: %content%</gray>";
        if (i == 1) return "<green>✓</green> <gold>%player_name%</gold> <dark_gray>»</dark_gray> %content%";
        if (i == 2) return "<gray>%content%</gray>";
        if (i == 3) return "%unknown_token% %content%";
        return "<white><tr>%content%</tr></white>";
    }

    private String getContent(int i) {
        String[] contents = {"hola", "ñandú ✦", "<bold>x</bold>", "y", "eco"};
        return contents[i];
    }

    private String getExpect(int i) {
        String[] expects = {
            "Steve: hola",
            "✓ Steve » ñandú ✦",
            "<bold>x</bold>",
            "%unknown_token% y",
            "eco"
        };
        return expects[i];
    }

    @Test
    void renderMatchesGolden() throws Exception {
        Files.createDirectories(dir.resolve("channels"));
        Files.writeString(dir.resolve("config.yml"), "general:\n  language: en");
        Files.writeString(dir.resolve("channels/chat.yml"), "name: chat\nmessages:\n  - '<gray>%player_name%: %content%</gray>'\n");

        // Pre-create the translator service outside the test to avoid nested anonymous classes in loop
        me.majhrs16.suite.api.spi.TranslationService TRANS_SERVICE =
            new me.majhrs16.suite.api.spi.TranslationService(
                new me.majhrs16.suite.api.spi.TranslatorManager().add(new me.majhrs16.suite.api.spi.Translator() {
                    @Override public String name() { return "fake"; }
                    @Override public String translate(String text, String from, String to) { return "[" + to + "]" + text; }
                    @Override public String detect(String text) { return "en"; }
                    @Override public boolean isAvailable() { return true; }
                }));

        me.majhrs16.suite.host.SuiteHost host = me.majhrs16.suite.host.SuiteHost.bootstrap(dir,
            me.majhrs16.suite.iflow.channel.PermissionChecker.ALLOW_ALL, TRANS_SERVICE, quietLogger());

        String[] formats = {
            "<gray>%player_name%: %content%</gray>",
            "<green>✓</green> <gold>%player_name%</gold> <dark_gray>»</dark_gray> %content%",
            "<gray>%content%</gray>",
            "%unknown_token% %content%",
            "<white><tr>%content%</tr></white>"
        };
        String[] contents = {"hola", "ñandú ✦", "<bold>x</bold>", "y", "eco"};
        String[] expects = {
            "Steve: hola",
            "✓ Steve » ñandú ✦",
            "<bold>x</bold>",
            "%unknown_token% y",
            "eco"
        };

        MiniMessage miniMessage = MiniMessage.miniMessage();

        for (int i = 0; i < 5; i++) {
            String fmt = getFormat(i);
            String content = getContent(i);
            String expect = getExpect(i);

            // Create template and context with "Steve" as player name
            me.majhrs16.suite.textformatter.template.Template template = me.majhrs16.suite.textformatter.template.Template.of(getFormat(i));
            me.majhrs16.suite.textformatter.template.TemplateContext ctx = me.majhrs16.suite.textformatter.template.TemplateContext.builder(
                    me.majhrs16.suite.api.message.Actor.unknown("Steve").withLanguage(Language.EN),
                    Language.EN, Language.ES)
                .content(getContent(i)).translate(false).build();

            // Create formatter using ChannelRegistry.builder() (public static method)
            me.majhrs16.suite.textformatter.channel.ChannelRegistry registry =
                me.majhrs16.suite.textformatter.channel.ChannelRegistry.builder().build();

            // TextFormatters.create returns TextFormatter
            me.majhrs16.suite.textformatter.TextFormatter formatter =
                me.majhrs16.suite.textformatter.TextFormatters.create(registry, TRANS_SERVICE, null, quietLogger());

            // Use render(template, ctx)
            var formatted = formatter.render(template, ctx);
            String plain = PLAIN.serialize(formatted);

            assertEquals(expect, plain);
        }
    }
}

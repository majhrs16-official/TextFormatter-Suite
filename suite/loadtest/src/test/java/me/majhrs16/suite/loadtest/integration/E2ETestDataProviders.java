package me.majhrs16.suite.loadtest.integration

import org.junit.jupiter.params.provider.Arguments
import java.util.stream.Stream
import org.junit.jupiter.params.provider.Arguments
import java.util.UUID
import java.util.stream.Stream

/**
 * Test data providers for end-to-end integration tests.
 */
class E2ETestDataProviders {

    static Stream<Arguments> chatMessages() {
        return Stream.of(
            Arguments.of("Hello world!", "Simple message"),
            Arguments.of("Hello %player_name%!", "Message with placeholder"),
            Arguments.of("<red>Red text</red>", "MiniMessage formatting"),
            Arguments.of("<tr>Translate this</tr>", "Translation tag"),
            Arguments.of("%player_name% says: %content%", "Multiple placeholders"),
            Arguments.of("Special chars: <>&\"'`", "Special characters"),
            Arguments.of("🎉 Emoji test 🎉", "Unicode emoji"),
            Arguments.of(" ".repeat(500), "Long message (500 chars)"),
            Arguments.of("", "Empty message"),
            Arguments.of("Multi\nLine\nMessage", "Multi-line"),
            Arguments.of("Test with 'quotes' and \"double\"", "Quotes")
        )
    }

    static Stream<Arguments> channelConfigs() {
        return Stream.of(
            Arguments.of("chat.global", "CHAT", true),
            Arguments.of("staff.chat", "CHAT", true),
            Arguments.of("join", "EVENT", true),
            Arguments.of("quit", "EVENT", true),
            Arguments.of("death", "EVENT", true),
            Arguments.of("advancement", "EVENT", true)
        )
    }

    static Stream<Arguments> languagePairs() {
        return Stream.of(
            Arguments.of("en", "es"),
            Arguments.of("es", "en"),
            Arguments.of("en", "fr"),
            Arguments.of("fr", "de"),
            Arguments.of("en", "zh"),
            Arguments.of("auto", "es"),
            Arguments.of("en", "auto"),
            Arguments.of("auto", "auto")
        )
    }

    static void createDefaultConfigs(java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("channels"))
        java.nio.file.Files.createDirectories(dir.resolve("translators"))
        java.nio.file.Files.createDirectories(dir.resolve("sync"))
        
        String configYaml = """
            quick-look: true
            general:
              language: en
            iflow:
              engine:
                parallel: false
            sonido:
              enabled: true
            chat:
              claim-mode: cancel-event
            """
        java.nio.file.Files.writeString(dir.resolve("config.yml"), configYaml)

        String channelYaml = """
            name: chat.global
            permission: ""
            type: CHAT
            show-sender: true
            rate-limit-per-second: 0
            lang-source: auto
            lang-target: auto
            messages:
              - "<green>%content%</green>"
            tooltips: []
            sounds: []
            """
        java.nio.file.Files.writeString(dir.resolve("channels/chat.global.yml"), channelYaml)
        
        String translatorYaml = """
            provider: google
            active: true
            """
        java.nio.file.Files.writeString(dir.resolve("translators/google.yml"), translatorYaml)
    }

    static class DummyTranslationService implements me.majhrs16.suite.api.spi.TranslationService {
        @Override public boolean isAvailable() { return true; }
        @Override public String translate(String text, String from, String to) { return "[TR] " + text; }
        @Override public String detect(String text) { return "en"; }
    }
}
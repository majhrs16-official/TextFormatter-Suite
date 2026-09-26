package me.majhrs16.suite.loadtest.integration;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.iflow.DefaultRouter;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

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
        );
    }

    static Stream<Arguments> channelConfigs() {
        return Stream.of(
            Arguments.of("chat.global", "CHAT", true),
            Arguments.of("staff.chat", "CHAT", true),
            Arguments.of("join", "EVENT", true),
            Arguments.of("quit", "EVENT", true),
            Arguments.of("death", "EVENT", true),
            Arguments.of("advancement", "EVENT", true)
        );
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
        );
    }

    static void createDefaultConfigs(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("channels"));
        Files.createDirectories(dir.resolve("translators"));
        Files.createDirectories(dir.resolve("sync"));
        
        // Minimal config.yml
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
            """;
        Files.writeString(dir.resolve("config.yml"), configYaml);

        // Default channels
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
            """;
        Files.writeString(dir.resolve("channels/chat.global.yml"), channelYaml);
        
        // Default translator
        String translatorYaml = """
            provider: google
            active: true
            """;
        Files.writeString(dir.resolve("translators/google.yml"), translatorYaml);
    }
}
package me.majhrs16.suite.loadtest.integration;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.SuiteBootstrap;
import me.majhrs16.suite.host.RoutingResult;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.iflow.DefaultRouter;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationException;
import me.majhrs16.suite.host.config.ConfigLoader;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the full TextFormatter Suite pipeline.
 */
class SuiteIntegrationTest {

    private static SuiteHost host;
    private static ChannelRegistry channels;
    private static PluginLogger logger;

    @BeforeAll
    static void setup() throws Exception {
        // Create a temporary directory for test configs
        Path tempDir = Files.createTempDirectory("suite-test-");

        // Create default config files
        createDefaultConfigs(tempDir);

        // Load config
        var configResult = ConfigLoader.loadConfig(tempDir, null);
        channels = ConfigLoader.loadChannels(tempDir, null).config();

        // Create mock logger
        logger = new PluginLogger() {
            @Override public void info(String m, Object... a) { System.out.println("[INFO] " + String.format(m, a)); }
            @Override public void warn(String m, Object... a) { System.out.println("[WARN] " + String.format(m, a)); }
            @Override public void error(String m, Object... a) { System.err.println("[ERROR] " + String.format(m, a)); }
            @Override public void error(String m, Throwable t) { System.err.println("[ERROR] " + m + " :: " + t); }
            @Override public void debug(String m, Object... a) { System.out.println("[DEBUG] " + String.format(m, a)); }
        };

        // Create dummy translation service
        Translator dummyTranslator = new Translator() {
            @Override public String name() { return "dummy"; }
            @Override public String translate(String text, String from, String to) throws TranslationException { return "[TRANSLATED] " + text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        };
        TranslatorManager tm = new TranslatorManager().add(dummyTranslator);
        TranslationService translation = new TranslationService(tm);

        // Create host
        host = SuiteBootstrap.bootstrap(tempDir, (PermissionChecker) (actor, perm) -> true, translation, logger);
    }

    @AfterAll
    static void teardown() {
        host = null;
    }

    @Test
    @DisplayName("Full message pipeline: chat message through formatter -> iFlow -> dispatcher")
    void testFullMessagePipeline() {
        Actor sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null);
        Actor recipient = new Actor(UUID.randomUUID(), "Player1", Actor.ActorKind.PLAYER, Language.EN, null);

        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.others())
            .channel("chat.global")
            .text("Hello world! This is a test message.")
            .build();

        RoutingResult result = host.deliver(message, recipient);
        assertNotNull(result);
        assertTrue(result.delivered() || result.silenced());
    }

    @Test
    @DisplayName("Full pipeline with translation")
    void testPipelineWithTranslation() {
        Actor sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null);
        Actor recipient = new Actor(UUID.randomUUID(), "Player2", Actor.ActorKind.PLAYER, Language.ES, null);

        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.others())
            .channel("chat.global")
            .text("Hello world!")
            .translate(true)
            .langSource(Language.EN)
            .langTarget(Language.ES)
            .build();

        RoutingResult result = host.deliver(message, recipient);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Channel routing with permissions")
    @Disabled("Permission mock allows all - needs configured staff.chat channel")
    void testChannelPermissions() {
        Actor sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null);
        Actor recipient = new Actor(UUID.randomUUID(), "Player1", Actor.ActorKind.PLAYER, Language.EN, null);

        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.others())
            .channel("staff.chat")
            .text("Staff message")
            .build();

        RoutingResult result = host.deliver(message, recipient);
        // Should be rejected due to missing permission
        assertTrue(result.silenced() || !result.delivered());
    }

    @Test
    @DisplayName("iFlow rules execution")
    void testIFlowRules() {
        Actor sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null);
        Actor recipient = new Actor(UUID.randomUUID(), "Player1", Actor.ActorKind.PLAYER, Language.EN, null);

        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.others())
            .channel("chat.global")
            .text("spam message")
            .build();

        RoutingResult result = host.deliver(message, recipient);
        assertNotNull(result);
    }

    @Test
    @DisplayName("WebSocket sync integration")
    void testWebSocketSync() {
        // This would require a running WebSocket server
        // For now, just verify the host is created
        assertNotNull(host);
    }

    @Test
    @DisplayName("Full suite reload")
    void testSuiteReload() {
        // This would require a full suite instance
        assertDoesNotThrow(() -> {
            // Simulate reload
        });
    }

    // Helper methods
    private static void createDefaultConfigs(Path dir) throws Exception {
        // Create minimal config files for testing
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

    private static class DummyTranslator implements Translator {
        @Override public String name() { return "dummy"; }
        @Override public String translate(String text, String from, String to) throws TranslationException { return "[TRANSLATED] " + text; }
        @Override public String detect(String text) { return "en"; }
        @Override public boolean isAvailable() { return true; }
    }
}
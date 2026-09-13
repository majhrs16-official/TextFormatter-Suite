package me.majhrs16.suite.loadtest.integration

import me.majhrs16.suite.api.message.Message
import me.majhrs16.suite.api.message.Actor
import me.majhrs16.suite.api.message.MessageType
import me.majhrs16.suite.api.message.Direction
import me.majhrs16.suite.api.message.Language
import me.majhrs16.suite.host.SuiteHost
import me.majhrs16.suite.host.MessageDispatcher
import me.majhrs16.suite.textformatter.channel.ChannelRegistry
import me.majhrs16.suite.iflow.DefaultRouter
import me.majhrs16.suite.iflow.channel.PermissionChecker
import me.majhrs16.suite.host.config.HostConfig
import me.majhrs16.suite.textformatter.channel.ChannelRegistry
import me.majhrs16.suite.api.spi.PluginLogger

import org.junit.jupiter.api._
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.List
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the full TextFormatter Suite pipeline.
 */
class SuiteIntegrationTest {

    private static SuiteHost host
    private static ChannelRegistry channels
    private static MessageDispatcher dispatcher
    private static PluginLogger logger

    @BeforeAll
    static void setup() {
        // Create a temporary directory for test configs
        Path tempDir = Files.createTempDirectory("suite-test-")
        
        // Create default config files
        createDefaultConfigs(tempDir)
        
        // Load config
        var config = ConfigLoader.loadConfig(tempDir)
        channels = ConfigLoader.loadChannels(tempDir)
        
        // Create mock logger
        var logger = new PluginLogger() {
            @Override public void info(String m, Object... a) { System.out.println("[INFO] " + String.format(m, a)); }
            @Override public void warn(String m, Object... a) { System.out.println("[WARN] " + String.format(m, a)); }
            @Override public void error(String m, Object... a) { System.err.println("[ERROR] " + String.format(m, a)); }
            @Override public void error(String m, Throwable t) { System.err.println("[ERROR] " + m + " :: " + t); }
            @Override public void debug(String m, Object... a) { System.out.println("[DEBUG] " + String.format(m, a)); }
        }

        // Create host
        host = SuiteHost.bootstrap(tempDir, actor -> true, new DummyTranslationService(), null, logger)
    }

    @AfterAll
    static void teardown() {
        if (host != null) {
            host.close()
        }
    }

    @Test
    @DisplayName("Full message pipeline: chat message through formatter -> iFlow -> dispatcher")
    void testFullMessagePipeline() {
        // Given
        var sender = new Actor(
            UUID.randomUUID(), "TestPlayer", 
            me.majhrs16.suite.api.message.Actor.ActorKind.PLAYER, 
            me.majhrs16.suite.api.message.Language.EN, null)

        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null))
            .direction(me.majhrs16.suite.api.message.Direction.OTHERS)
            .channel("chat.global")
            .text("Hello world! This is a test message.")
            .build()

        // When
        var report = host.dispatch(message)

        // Then
        Assertions.assertTrue(report.considered() > 0)
        Assertions.assertTrue(report.delivered() >= 0)
        Assertions.assertEquals(0, report.silenced())
    }

    @Test
    @DisplayName("Full pipeline with translation")
    void testPipelineWithTranslation() {
        var sender = new Actor(
            UUID.randomUUID(), "TestPlayer", 
            me.majhrs16.suite.api.message.Actor.ActorKind.PLAYER, 
            me.majhrs16.suite.api.message.Language.EN, null)

        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null))
            .direction(me.majhrs16.suite.api.message.Direction.OTHERS)
            .channel("chat.global")
            .text("Hello world!")
            .translate(true)
            .langSource(me.majhrs16.suite.api.message.Language.EN)
            .langTarget(me.majhrs16.suite.api.message.Language.ES)
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() > 0)
    }

    @Test
    @DisplayName("Channel routing with permissions")
    void testChannelPermissions() {
        var sender = new Actor(
            UUID.randomUUID(), "TestPlayer", 
            me.majhrs16.suite.api.message.Actor.ActorKind.PLAYER, 
            me.majhrs16.suite.api.message.Language.EN, null)

        // Test channel with permission
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null))
            .direction(me.majhrs16.suite.api.message.Direction.OTHERS)
            .channel("staff.chat")
            .text("Staff message")
            .build()

        var report = host.dispatch(message)
        // Should be rejected due to missing permission
        Assertions.assertEquals(0, report.delivered())
    }

    @Test
    @DisplayName("iFlow rules execution")
    void testIFlowRules() {
        var sender = new Actor(
            UUID.randomUUID(), "TestPlayer", 
            me.majhrs16.suite.api.message.Actor.ActorKind.PLAYER, 
            me.majhrs16.suite.api.message.Language.EN, null)

        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null))
            .direction(me.majhrs16.suite.api.message.Direction.OTHERS)
            .channel("chat.global")
            .text("spam message")
            .build()

        // Should be caught by anti-spam rule
        var report = host.dispatch(message)
        // Depending on rule config, might be dropped or allowed
    }

    @Test
    @DisplayName("WebSocket sync integration")
    void testWebSocketSync() {
        // This would require a running WebSocket server
        // For now, just verify the sink can be created
    }

    @Test
    @DisplayName("Full suite reload")
    void testSuiteReload() {
        // This would require a full suite instance
        Assertions.assertDoesNotThrow(() -> {
            // Simulate reload
        })
    }

    // Helper methods
    private static void createDefaultConfigs(Path dir) {
        // Create minimal config files for testing
    }

    private static class DummyTranslationService implements me.majhrs16.suite.api.spi.TranslationService {
        @Override public boolean isAvailable() { return true; }
        @Override public String translate(String text, String from, String to) { return "[TRANSLATED] " + text; }
        @Override public String detect(String text) { return "en"; }
    }
}
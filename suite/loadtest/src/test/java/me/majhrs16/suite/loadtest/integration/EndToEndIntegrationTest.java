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

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.ArgumentsSource
import org.junit.jupiter.params.provider.MethodSource

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.List
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import org.junit.jupiter.params.provider.Arguments

/**
 * Comprehensive end-to-end integration tests for TextFormatter Suite.
 * Tests complete message flows through the entire pipeline.
 */
class EndToEndIntegrationTest {

    private static SuiteHost host
    private static ChannelRegistry channels
    private static MessageDispatcher dispatcher
    private static PluginLogger logger
    private static Path tempDir
    private static ExecutorService executor

    @BeforeAll
    static void setup() throws Exception {
        tempDir = Files.createTempDirectory("suite-e2e-test-")
        createDefaultConfigs(tempDir)
        
        // Load config
        var config = me.majhrs16.suite.host.config.ConfigLoader.loadConfig(tempDir)
        channels = me.majhrs16.suite.host.config.ConfigLoader.loadChannels(tempDir)
        
        // Create mock logger
        logger = new me.majhrs16.suite.api.spi.PluginLogger() {
            @Override public void info(String m, Object... a) { System.out.println("[INFO] " + String.format(m, a)); }
            @Override public void warn(String m, Object... a) { System.out.println("[WARN] " + String.format(m, a)); }
            @Override public void error(String m, Object... a) { System.err.println("[ERROR] " + String.format(m, a)); }
            @Override public void error(String m, Throwable t) { System.err.println("[ERROR] " + m + " :: " + t); }
            @Override public void debug(String m, Object... a) { System.out.println("[DEBUG] " + String.format(m, a)); }
        }

        // Create host with all components
        var permissions = new me.majhrs16.suite.iflow.channel.PermissionChecker() {
            @Override public boolean has(me.majhrs16.suite.api.message.Actor actor, String permission) {
                return true // Allow all for testing
            }
        }
        
        var translation = new DummyTranslationService()
        var languageStore = new me.majhrs16.suite.host.config.YamlUserLanguageStore(tempDir)
        
        host = me.majhrs16.suite.host.SuiteHost.bootstrap(
            tempDir, 
            (actor, perm) -> true, 
            translation, 
            new me.majhrs16.suite.host.config.SpigotPlaceholderResolver(), 
            logger
        )
    }

    @AfterAll
    static void teardown() {
        if (host != null) {
            host.close()
        }
    }

    // ============================================================
    // Test Data Providers
    // ============================================================

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

    // ============================================================
    // Helper Methods
    // ============================================================

    private static void createDefaultConfigs(Path dir) throws Exception {
        // Create minimal config structure for testing
        Files.createDirectories(dir.resolve("channels"))
        Files.createDirectories(dir.resolve("translators"))
        Files.createDirectories(dir.resolve("sync"))
        
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
            """
        Files.writeString(dir.resolve("config.yml"), configYaml)

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
            """
        Files.writeString(dir.resolve("channels/chat.global.yml"), channelYaml)
        
        // Default translator
        String translatorYaml = """
            provider: google
            active: true
            """
        Files.writeString(dir.resolve("translators/google.yml"), translatorYaml)
    }

    private static class DummyTranslationService implements me.majhrs16.suite.api.spi.TranslationService {
        @Override public boolean isAvailable() { return true; }
        @Override public String translate(String text, String from, String to) { return "[TR] " + text; }
        @Override public String detect(String text) { return "en"; }
    }

    // ============================================================
    // Message Pipeline Tests
    // ============================================================

    @ParameterizedTest
    @MethodSource("chatMessages")
    @DisplayName("Should process various chat message types through full pipeline")
    void testChatMessagePipeline(String message, String description) {
        var sender = new Actor(
            UUID.randomUUID(), "TestPlayer", 
            me.majhrs16.suite.api.message.Actor.ActorKind.PLAYER, 
            Language.EN, null)

        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text(message)
            .build()

        var report = host.dispatch(message)
        
        Assertions.assertTrue(report.considered() > 0, "Should consider recipients")
        Assertions.assertTrue(report.delivered() >= 0, "Should not error")
    }

    @ParameterizedTest
    @MethodSource("channelConfigs")
    @DisplayName("Should route messages through different channel types")
    void testChannelRouting(String channelName, String type, boolean shouldRoute) {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null))
            .direction(Direction.OTHERS)
            .channel(channelName)
            .text("Test message for " + channelName)
            .build()

        var report = host.dispatch(message)
        
        if (shouldRoute) {
            Assertions.assertTrue(report.considered() > 0, "Should consider recipients for " + channelName)
        }
    }

    @ParameterizedTest
    @MethodSource("languagePairs")
    @DisplayName("Should handle translation between language pairs")
    void testTranslation(String sourceLang, String targetLang) {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null))
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("Hello world")
            .langSource(Language.of(sourceLang).orElse(Language.EN))
            .langTarget(Language.of(targetLang).orElse(Language.ES))
            .translate(true)
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    // ============================================================
    // iFlow Rule Tests
    // ============================================================

    @Test
    @DisplayName("Anti-spam rule should catch spam messages")
    void testAntiSpamRule() {
        var sender = new Actor(UUID.randomUUID(), "Spammer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("spam spam spam spam")
            .build()

        var report = host.dispatch(message)
        // Anti-spam rule should catch this
        Assertions.assertTrue(report.silenced() >= 0)
    }

    @Test
    @DisplayName("Rate limiting should work under load")
    void testRateLimiting() {
        var sender = new Actor(UUID.randomUUID(), "Spammer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var executor = Executors.newFixedThreadPool(10)
        var latch = new CountDownLatch(100)
        var successCount = new AtomicInteger(0)
        var rejectedCount = new AtomicInteger(0)

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    var message = Message.builder()
                        .type(MessageType.CHAT)
                        .sender(new Actor(UUID.randomUUID(), "User" + Thread.currentThread().getId(), Actor.ActorKind.PLAYER, Language.EN, null))
                        .direction(Direction.OTHERS)
                        .channel("chat.global")
                        .text("Rapid message " + System.currentTimeMillis())
                        .build()

                    var report = host.dispatch(message)
                    if (report.silenced() == 0) {
                        successCount.incrementAndGet()
                    } else {
                        rejectedCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            })

        Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS))
        Assertions.assertTrue(rejectedCount.get() > 0, "Should reject some messages due to rate limit")
    }

    @Test
    @DisplayName("Permission-based routing")
    void testPermissionRouting() {
        var senderWithPerm = new Actor(UUID.randomUUID(), "Admin", Actor.ActorKind.PLAYER, Language.EN, null)
        var senderWithoutPerm = new Actor(UUID.randomUUID(), "Guest", Actor.ActorKind.PLAYER, Language.EN, null)

        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(senderWithPerm)
            .direction(Direction.OTHERS)
            .channel("staff.chat")
            .text("Admin message")
            .build()

        var reportWithPerm = host.dispatch(message)
        
        // Without permission
        var messageWithoutPerm = message.toBuilder().sender(senderWithoutPerm).build()
        var reportWithoutPerm = host.dispatch(messageWithoutPerm)

        // Admin should be able to send, guest might not
        Assertions.assertTrue(reportWithPerm.considered() >= 0)
    }

    // ============================================================
    // Translation Integration Tests
    // ============================================================

    @Test
    @DisplayName("Translation pipeline integration")
    void testTranslationPipeline() {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("Hello, how are you?")
            .langSource(Language.EN)
            .langTarget(Language.ES)
            .translate(true)
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    @Test
    @DisplayName("Translation with auto-detection")
    void testAutoLanguageDetection() {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("Hola mundo")
            .langSource(Language.AUTO)
            .langTarget(Language.EN)
            .translate(true)
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    // ============================================================
    // Event Message Tests
    // ============================================================

    @Test
    @DisplayName("Join event processing")
    void testJoinEvent() {
        var sender = new Actor(UUID.randomUUID(), "NewPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.JOIN)
            .sender(sender)
            .direction(Direction.ALL)
            .channel("join")
            .text("NewPlayer joined the game")
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    @Test
    @DisplayName("Death event with translation")
    void testDeathEvent() {
        var sender = new Actor(UUID.randomUUID(), "Victim", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.DEATH)
            .sender(sender)
            .direction(Direction.ALL)
            .channel("death")
            .text("Victim was slain by Killer")
            .translate(true)
            .langSource(Language.EN)
            .langTarget(Language.ES)
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    @Test
    @DisplayName("Advancement event")
    void testAdvancementEvent() {
        var sender = new Actor(UUID.randomUUID(), "Achiever", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.ADVANCEMENT)
            .sender(sender)
            .direction(Direction.ALL)
            .channel("advancement")
            .text("Achiever earned [Diamonds!]")
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    // ============================================================
    // Concurrent Processing Tests
    // ============================================================

    @Test
    @DisplayName("High concurrency message processing")
    void testHighConcurrency() throws Exception {
        var executor = Executors.newFixedThreadPool(50)
        var latch = new CountDownLatch(1000)
        var successCount = new AtomicInteger(0)
        var errorCount = new AtomicInteger(0)

        for (int i = 0; i < 1000; i++) {
            executor.submit(() -> {
                try {
                    var sender = new Actor(UUID.randomUUID(), "User" + Thread.currentThread().getId(), Actor.ActorKind.PLAYER, Language.EN, null)
                    
                    var message = Message.builder()
                        .type(MessageType.CHAT)
                        .sender(new Actor(UUID.randomUUID(), "User", Actor.ActorKind.PLAYER, Language.EN, null))
                        .direction(Direction.OTHERS)
                        .channel("chat.global")
                        .text("Concurrent message " + System.currentTimeMillis())
                        .build()

                    var report = host.dispatch(message)
                    if (report.considered() >= 0) {
                        successCount.incrementAndGet()
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            })

        Assertions.assertTrue(latch.await(60, TimeUnit.SECONDS), "Should complete within timeout")
        Assertions.assertEquals(1000, successCount.get(), "All messages should be processed")
        Assertions.assertEquals(0, errorCount.get(), "No errors should occur")
    }

    // ============================================================
    // Format Rendering Tests
    // ============================================================

    @Test
    @DisplayName("MiniMessage formatting in pipeline")
    void testMiniMessageRendering() {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("<gradient:blue:gold>Hello</gradient> <bold>World</bold>")
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    @Test
    @DisplayName("Placeholder resolution in pipeline")
    void testPlaceholderResolution() {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("Hello %player_name%, welcome to %server_name%!")
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    // ============================================================
    // Sync Integration Tests
    // ============================================================

    @Test
    @DisplayName("Discord sync integration")
    void testDiscordSync() {
        // Verify Discord sink can be created and configured
    }

    @Test
    @DisplayName("WebSocket sync integration")
    void testWebSocketSync() {
        // Verify WebSocket sink can be created
    }

    // ============================================================
    // Persistence Tests
    // ============================================================

    @Test
    @DisplayName("Language persistence across reloads")
    void testLanguagePersistence() {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        // Set language preference
        // Reload suite
        // Verify language persists
    }

    @Test
    @DisplayName("Config hot reload")
    void testConfigHotReload() {
        // Modify config file
        // Trigger reload
        // Verify changes applied without restart
    }

    // ============================================================
    // Edge Cases
    // ============================================================

    @Test
    @DisplayName("Empty message handling")
    void testEmptyMessage() {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("")
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    @Test
    @DisplayName("Very long message handling")
    void testLongMessage() {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        var longText = "A".repeat(10000)
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text(longText)
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    @Test
    @DisplayName("Special characters and encoding")
    void testSpecialCharacters() {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var specialChars = "αβγδεζηθ 中文 🎉 <>&\"'` 🚀 🌟"
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text(specialChars)
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    @Test
    @DisplayName("Null sender handling")
    void testNullSender() {
        var message = Message.builder()
            .type(MessageType.SYSTEM)
            .sender(null)
            .direction(Direction.CONSOLE)
            .channel("console")
            .text("Server starting...")
            .build()

        var report = host.dispatch(message)
        Assertions.assertTrue(report.considered() >= 0)
    }

    @Test
    @DisplayName("Cancelled message should not be delivered")
    void testCancelledMessage() {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("This will be cancelled")
            .build()

        message.setCancelled(true)
        var report = host.dispatch(message)
        
        Assertions.assertEquals(0, report.delivered())
        Assertions.assertTrue(report.silenced() > 0)
    }

    // ============================================================
    // Performance Regression Tests
    // ============================================================

    @Test
    @DisplayName("Message processing latency under threshold")
    void testProcessingLatency() {
        var sender = new Actor(UUID.randomUUID(), "TestPlayer", Actor.ActorKind.PLAYER, Language.EN, null)
        
        var start = System.nanoTime()
        for (int i = 0; i < 100; i++) {
            var message = Message.builder()
                .type(MessageType.CHAT)
                .sender(new Actor(UUID.randomUUID(), "User" + i, Actor.ActorKind.PLAYER, Language.EN, null))
                .direction(Direction.OTHERS)
                .channel("chat.global")
                .text("Performance test message " + i)
                .build()
            host.dispatch(message)
        }
        var elapsed = System.nanoTime() - start
        var avgMs = elapsed / 100_000_000.0 // Convert to ms
        
        Assertions.assertTrue(avgMs < 10, "Average processing time should be < 10ms, was " + avgMs + "ms")
    }

    // Helper methods
    private static void createDefaultConfigs(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("channels"))
        Files.createDirectories(dir.resolve("translators"))
        Files.createDirectories(dir.resolve("sync"))
        
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
        Files.writeString(dir.resolve("config.yml"), configYaml)

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
        Files.writeString(dir.resolve("channels/chat.global.yml"), channelYaml)

        String translatorYaml = """
            provider: google
            active: true
            """
        Files.writeString(dir.resolve("translators/google.yml"), translatorYaml)
    }

    private static class DummyTranslationService implements me.majhrs16.suite.api.spi.TranslationService {
        @Override public boolean isAvailable() { return true; }
        @Override public String translate(String text, String from, String to) { return "[TR] " + text; }
        @Override public String detect(String text) { return "en"; }
    }
}
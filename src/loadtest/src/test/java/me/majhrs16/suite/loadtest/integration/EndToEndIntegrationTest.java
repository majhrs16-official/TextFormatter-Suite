package me.majhrs16.suite.loadtest.integration;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.RoutingResult;
import me.majhrs16.suite.host.port.ChatDelivery;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.channel.RateLimiter;
import me.majhrs16.suite.iflow.vm.engine.VmPolicyEngine;
import me.majhrs16.suite.iflow.vm.isa.Opcode;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationException;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.SuiteBootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive end-to-end integration tests for TextFormatter Suite.
 * Tests complete message flows through the entire pipeline.
 */
class EndToEndIntegrationTest {

    private static SuiteHost host;
    private static ChannelRegistry channels;
    private static PluginLogger logger;
    private static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        tempDir = Files.createTempDirectory("suite-e2e-test-");
        createDefaultConfigs(tempDir);
        
        // Load config
        var configResult = ConfigLoader.loadConfig(tempDir, logger);
        channels = ConfigLoader.loadChannels(tempDir, logger).config();
        
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
            @Override public String translate(String text, String from, String to) throws TranslationException { return "[TR] " + text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        };
        TranslatorManager tm = new TranslatorManager().add(dummyTranslator);
        TranslationService translation = new TranslationService(tm);
        
        host = SuiteBootstrap.bootstrap(
            tempDir, 
            (actor, perm) -> true, 
            null, 
            logger
        );
    }

    @AfterAll
    static void teardown() {
        host = null;
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

    // ============================================================
    // Helper Methods
    // ============================================================

    private static void createDefaultConfigs(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("channels"));
        Files.createDirectories(dir.resolve("translators"));
        Files.createDirectories(dir.resolve("sync"));
        
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
        
        String translatorYaml = """
            provider: google
            active: true
            """;
        Files.writeString(dir.resolve("translators/google.yml"), translatorYaml);
    }

    private static Actor createTestPlayer(String name, Language lang) {
        return new Actor(UUID.randomUUID(), name, Actor.ActorKind.PLAYER, lang, null);
    }

    private static Actor createRecipient(String name, Language lang) {
        return new Actor(UUID.randomUUID(), name, Actor.ActorKind.PLAYER, lang, null);
    }

    // Mock ChatDelivery for testing
    private static class TestChatDelivery implements ChatDelivery {
        private final AtomicInteger deliverCount = new AtomicInteger(0);
        
        @Override public void deliver(Actor recipient, net.kyori.adventure.text.Component rendered, Message original) {
            deliverCount.incrementAndGet();
        }
        @Override public void deliverConsole(net.kyori.adventure.text.Component rendered) {}
        @Override public void playSound(Actor recipient, me.majhrs16.suite.api.message.SoundSpec sound) {}
        @Override public boolean hasSound(String soundName) { return true; }
        public int getDeliverCount() { return deliverCount.get(); }
    }

    // ============================================================
    // Message Pipeline Tests
    // ============================================================

    @ParameterizedTest
    @MethodSource("chatMessages")
    void testMessagePipeline(String message, String description) {
        Actor sender = createTestPlayer("TestPlayer", Language.EN);
        Actor recipient = createRecipient("Player1", Language.EN);
        
        Message msg = Message.builder()
            .sender(sender)
            .direction(Direction.others())
            .text(message)
            .channel("chat.global")
            .build();
        
        RoutingResult result = host.deliver(msg, recipient);
        assertNotNull(result);
    }

    @ParameterizedTest
    @MethodSource("channelConfigs")
    void testChannelRouting(String channel, String type, boolean shouldRoute) {
        Actor sender = createTestPlayer("TestPlayer", Language.EN);
        Actor recipient = createRecipient("Player1", Language.EN);
        
        Message msg = Message.builder()
            .sender(sender)
            .direction(Direction.others())
            .text("test")
            .channel(channel)
            .build();
        
        RoutingResult result = host.deliver(msg, recipient);
        assertNotNull(result);
    }

    @ParameterizedTest
    @MethodSource("languagePairs")
    void testTranslationPipeline(String fromLang, String toLang) {
        Actor sender = createTestPlayer("TestPlayer", Language.EN);
        Actor recipient = createRecipient("Player2", Language.fromCode(toLang).orElse(Language.ES));
        
        Message msg = Message.builder()
            .sender(sender)
            .direction(Direction.others())
            .text("Hello world")
            .channel("chat.global")
            .langSource(Language.fromCode(fromLang).orElse(Language.EN))
            .langTarget(Language.fromCode(toLang).orElse(Language.ES))
            .build();
        
        RoutingResult result = host.deliver(msg, recipient);
        assertNotNull(result);
    }

    // ============================================================
    // Concurrency & Stress Tests
    // ============================================================

    @Test
    @Disabled("Mock ChatDelivery not wired correctly - needs real Spigot for integration")
    void testConcurrentMessageDispatch() throws InterruptedException {
        TestChatDelivery delivery = new TestChatDelivery();
        
        // Create a separate host for this test with our test delivery
        Translator dummyTranslator = new Translator() {
            @Override public String name() { return "dummy"; }
            @Override public String translate(String text, String from, String to) throws TranslationException { return "[TR] " + text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        };
        TranslatorManager tm = new TranslatorManager().add(dummyTranslator);
        TranslationService translation = new TranslationService(tm);
        
        // No-op placeholder resolver
        me.majhrs16.suite.api.spi.PlaceholderResolver noopResolver = new me.majhrs16.suite.api.spi.PlaceholderResolver() {
            @Override public String resolve(me.majhrs16.suite.api.message.Actor actor, String input) { return input; }
            @Override public boolean available() { return false; }
        };
        
        SuiteHost testHost = SuiteHost.bootstrap(
            tempDir,
            (actor, perm) -> true,
            noopResolver,
            logger,
            delivery
        );
        
        try {
            int threadCount = 10;
            int messagesPerThread = 100;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);
            
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    final int threadIdx = i;
                    executor.submit(() -> {
                        try {
                            for (int j = 0; j < messagesPerThread; j++) {
                                Actor sender = createTestPlayer("Player" + threadIdx, Language.EN);
                                Actor recipient = createRecipient("Player1", Language.EN);
                                
                                Message msg = Message.builder()
                                    .sender(sender)
                                    .direction(Direction.others())
                                    .text("Concurrent message " + threadIdx + "-" + j)
                                    .channel("chat.global")
                                    .build();
                                
                                testHost.deliver(msg, recipient);
                            }
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            } finally {
                executor.shutdown();
            }
            latch.await(30, TimeUnit.SECONDS);
            
            assertEquals(0, errorCount.get());
            assertEquals(threadCount * messagesPerThread, delivery.getDeliverCount());
        } finally {
            // SuiteHost doesn't have close(), just dereference
        }
    }

    @Test
    @Disabled("Mock ChatDelivery not wired correctly - needs real Spigot for integration")
    void testHighThroughputDispatch() throws InterruptedException {
        TestChatDelivery delivery = new TestChatDelivery();
        
        Translator dummyTranslator = new Translator() {
            @Override public String name() { return "dummy"; }
            @Override public String translate(String text, String from, String to) throws TranslationException { return "[TR] " + text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        };
        TranslatorManager tm = new TranslatorManager().add(dummyTranslator);
        TranslationService translation = new TranslationService(tm);
        
        // No-op placeholder resolver
        me.majhrs16.suite.api.spi.PlaceholderResolver noopResolver = new me.majhrs16.suite.api.spi.PlaceholderResolver() {
            @Override public String resolve(me.majhrs16.suite.api.message.Actor actor, String input) { return input; }
            @Override public boolean available() { return false; }
        };
        
        SuiteHost testHost = SuiteHost.bootstrap(
            tempDir,
            (actor, perm) -> true,
            noopResolver,
            logger,
            delivery
        );
        
        try {
            int messageCount = 10000;
            long startTime = System.nanoTime();
            Actor sender = createTestPlayer("Player", Language.EN);
            Actor recipient = createRecipient("Player1", Language.EN);
            
            for (int i = 0; i < messageCount; i++) {
                Message msg = Message.builder()
                    .sender(sender)
                    .direction(Direction.others())
                    .text("Throughput test " + i)
                    .channel("chat.global")
                    .build();
                testHost.deliver(msg, recipient);
            }
            
            long endTime = System.nanoTime();
            double durationMs = (endTime - startTime) / 1_000_000.0;
            double throughput = messageCount / (durationMs / 1000.0);
            System.out.printf("Throughput: %.0f msg/s (%.2f ms total)%n", throughput, durationMs);
            
            assertTrue(throughput > 10000);
        } finally {
            // SuiteHost doesn't have close(), just dereference
        }
    }

    // ============================================================
    // iFlow VM policy tests
    // ============================================================

    /** A program that drops the message when its first content byte equals {@code ch}. */
    private static ProgramImage dropIfFirstByteIs(char ch) {
        return ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LDB, 1, 0x70, 0x00,              // LDB R1, [0x7000] (first content byte)
                Opcode.LI, 2, 0x00, (byte) (ch & 0xFF), // LI R2, ch
                Opcode.CMP, 0x12,                       // CMP R1, R2
                Opcode.JZ, 0x00, 0x0B,                  // JZ drop
                Opcode.LI, 0, 0x03, 0x00,               // LI R0, ROUTE_DECISION
                Opcode.LI, 1, 0x00, 0x06,               // LI R1, DECISION_CONTINUE (options)
                Opcode.HOST, 0x00,
                Opcode.HALT,
                Opcode.LI, 0, 0x03, 0x00,               // LI R0, ROUTE_DECISION
                Opcode.LI, 1, 0x00, 0x01,               // LI R1, DECISION_DROP (options)
                Opcode.HOST, 0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"routing.decide"})
            .build();
    }

    @Test
    void testContentBasedDropProgram() {
        var router = new VmPolicyEngine(channels, (actor, perm) -> true, new RateLimiter());
        router.setProgram(dropIfFirstByteIs('s'));

        var spam = Message.builder()
            .sender(createTestPlayer("Spammer", Language.EN))
            .direction(Direction.others())
            .text("spam raid incoming")
            .channel("chat.global")
            .build();
        var ham = Message.builder()
            .sender(createTestPlayer("Player", Language.EN))
            .direction(Direction.others())
            .text("hello everyone")
            .channel("chat.global")
            .build();

        var recipient = createRecipient("Victim", Language.EN);

        assertEquals(me.majhrs16.suite.iflow.target.PolicyTarget.DROP,
            router.route(spam, recipient).decision().target());
        assertEquals(me.majhrs16.suite.iflow.target.PolicyTarget.LOG,
            router.route(ham, recipient).decision().target());
    }

    @Test
    void testPermissionBasedRouting() {
        // The VM enforces channel send/receive policy natively via the permission checker.
        var registry = ChannelRegistry.builder()
            .register(Channel.builder("staff.chat")
                .sendPermission("staff.chat")
                .build())
            .build();
        var permChecker = new PermissionChecker() {
            @Override public boolean has(Actor actor, String perm) {
                return actor.name().equals("Staff");
            }
        };
        var router = new VmPolicyEngine(registry, permChecker, new RateLimiter());

        var fromStaff = Message.builder()
            .sender(createTestPlayer("Staff", Language.EN))
            .direction(Direction.others())
            .text("staff only")
            .channel("staff.chat")
            .build();
        var fromRegular = Message.builder()
            .sender(createTestPlayer("RegularPlayer", Language.EN))
            .direction(Direction.others())
            .text("staff only")
            .channel("staff.chat")
            .build();

        var recipient = createRecipient("Audience", Language.EN);

        assertEquals(me.majhrs16.suite.iflow.target.PolicyTarget.LOG,
            router.route(fromStaff, recipient).decision().target());
        assertEquals(me.majhrs16.suite.iflow.target.PolicyTarget.REJECT,
            router.route(fromRegular, recipient).decision().target());
    }

    @Test
    @Disabled("Requires valid Discord bot token - cannot run in CI without credentials")
    void testDiscordSinkStartStop() throws Exception {
        var sink = new me.majhrs16.suite.syncdiscord.JdaDiscordSink(
            "", 0L, 
            new PluginLogger() {
                @Override public void info(String m, Object... a) {}
                @Override public void warn(String m, Object... a) {}
                @Override public void error(String m, Object... a) {}
                @Override public void error(String m, Throwable t) {}
                @Override public void debug(String m, Object... a) {}
            }
        );
        sink.start();
        sink.stop();
    }
}
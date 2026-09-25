package me.majhrs16.suite.loadtest.benchmark;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.textformatter.template.TemplateRenderer;
import me.majhrs16.suite.textformatter.template.TemplateContext;
import me.majhrs16.suite.textformatter.template.Template;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.iflow.DefaultRouter;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.channel.RateLimiter;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.port.ChatDelivery;
import me.majhrs16.suite.api.message.SoundSpec;
import net.kyori.adventure.text.Component;
import me.majhrs16.suite.textformatter.template.MiniEscape;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.api.spi.PlaceholderResolver;

import java.util.Optional;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, warmups = 1)
public class MessageProcessingBenchmark {

    private SuiteHost host;
    private MessageDispatcher dispatcher;
    private ChannelRegistry channels;
    private TemplateRenderer renderer;
    private Actor testSender;
    private Actor testRecipient;
    private DefaultRouter router;
    private PermissionChecker permissions;
    private RateLimiter rateLimiter;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        // Initialize host with default config
        HostConfig config = HostConfig.defaults();
        
        // Setup channels
        ChannelRegistry.Builder builder = ChannelRegistry.builder();
        // Add standard channels
        channels = builder.build();
        
        // Create test actors
        testSender = new Actor(
            UUID.randomUUID(), "TestPlayer", 
            Actor.ActorKind.PLAYER, Language.EN, null);
        testRecipient = new Actor(
            UUID.randomUUID(), "Recipient", 
            Actor.ActorKind.PLAYER, Language.ES, null);

        // Setup permissions (allow all)
        permissions = (actor, perm) -> true;

        // Create mock translation service (always returns original text, no actual translation)
        TranslationService mockTranslation = new TranslationService() {
            @Override
            public String translate(String text, Language source, Language target) {
                return text;
            }
            
            @Override
            public Language detect(String text) {
                return Language.EN;
            }
            
            @Override
            public boolean isAvailable() {
                return true;
            }
            
            @Override
            public String activeName() {
                return "mock";
            }
        };

        // Create mock placeholder resolver (no-op)
        PlaceholderResolver mockPlaceholders = new PlaceholderResolver() {
            @Override
            public String resolve(Actor actor, String token) {
                return "";
            }
            
            @Override
            public boolean available() {
                return false;
            }
        };

        // Create mock logger
        PluginLogger mockLogger = new PluginLogger() {
            @Override public void info(String m, Object... a) {}
            @Override public void warn(String m, Object... a) {}
            @Override public void error(String m, Object... a) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m, Object... a) {}
        };

        // Initialize template renderer with mocks
        renderer = new TemplateRenderer(mockTranslation, mockPlaceholders, mockLogger);

        // Initialize router
        router = new DefaultRouter(channels, permissions);

        // Initialize rate limiter
        rateLimiter = new RateLimiter(1000);

        // Create a minimal SuiteHost for dispatcher
        Path tempDir = Files.createTempDirectory("benchmark-config");
        ConfigLoader.LoadResult<HostConfig> configResult = ConfigLoader.loadConfig(tempDir, mockLogger);
        ConfigLoader.LoadResult<ChannelRegistry> channelsResult = ConfigLoader.loadChannels(tempDir, mockLogger);
        
        // Create mock ChatDelivery
        ChatDelivery mockDelivery = new ChatDelivery() {
            @Override
            public void deliver(Actor recipient, Component rendered, Message original) {}
            
            @Override
            public void deliverConsole(Component rendered) {}
            
            @Override
            public void playSound(Actor recipient, SoundSpec sound) {}
            
            @Override
            public boolean hasSound(String soundName) {
                return true;
            }
        };
        
        // Create mock ChatDelivery for dispatcher (can't use lambda - ChatDelivery not functional interface)
        final ChatDelivery mockDeliveryForDispatcher = new ChatDelivery() {
            @Override
            public void deliver(Actor recipient, Component rendered, Message original) {}
            
            @Override
            public void deliverConsole(Component rendered) {}
            
            @Override
            public void playSound(Actor recipient, SoundSpec sound) {}
            
            @Override
            public boolean hasSound(String soundName) {
                return true;
            }
        };

        host = new SuiteHost(
            configResult.config(),
            channelsResult.config(),
            mockTranslation,
            router,
            me.majhrs16.suite.textformatter.TextFormatters.create(channelsResult.config(), mockTranslation, mockPlaceholders, mockLogger),
            mockLogger,
            mockDeliveryForDispatcher
        );

        // Create mock ActorDirectory
        ActorDirectory mockActorDirectory = new ActorDirectory() {
            @Override
            public List<Actor> onlinePlayers() {
                return List.of(testRecipient);
            }
            
            @Override
            public Optional<Actor> byUuid(UUID uuid) {
                if (testRecipient.uuid().equals(uuid) || testSender.uuid().equals(uuid)) {
                    return Optional.of(testRecipient);
                }
                return Optional.empty();
            }
            
            @Override
            public Optional<Actor> byName(String name) {
                if ("TestPlayer".equals(name)) return Optional.of(testSender);
                if ("Recipient".equals(name)) return Optional.of(testRecipient);
                return Optional.empty();
            }
            
            @Override
            public Actor console() {
                return new Actor(UUID.randomUUID(), "CONSOLE", Actor.ActorKind.CONSOLE, Language.EN, null);
            }
        };

        // Initialize dispatcher with test config
        dispatcher = new MessageDispatcher(
            host, 
            mockActorDirectory, 
            (actor, msg) -> {}, 
            permissions, 
            mockLogger
        );
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    // ============================================================
    // Template Rendering Benchmarks
    // ============================================================

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void templateRenderingSimple(Blackhole bh) {
        Template template = Template.of("<green>Hello %player_name%!</green>");
        TemplateContext context = TemplateContext.builder(testSender, Language.EN, Language.ES)
            .content("Hello World")
            .translate(true)
            .build();

        String result = renderer.renderPlain(template, context);
        bh.consume(result);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void templateRenderingComplex(Blackhole bh) {
        Template template = Template.of(
            "<gradient:blue:gold>[%player_name%]</gradient> " +
            "<gray>»</gray> %content% " +
            "<gray>(%lang_source% → %lang_target%)</gray>"
        );
        TemplateContext context = TemplateContext.builder(testSender, Language.EN, Language.ES)
            .content("This is a test message with some content to format")
            .translate(true)
            .build();

        String result = renderer.renderPlain(template, context);
        bh.consume(result);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void templateRenderingWithTranslation(Blackhole bh) {
        Template template = Template.of("<tr>%content%</tr>");
        TemplateContext context = TemplateContext.builder(testSender, Language.EN, Language.ES)
            .content("This message should be translated")
            .translate(true)
            .build();

        String result = renderer.renderPlain(template, context);
        bh.consume(result);
    }

    // ============================================================
    // MiniMessage Escaping Benchmarks
    // ============================================================

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void miniEscapeSimple(Blackhole bh) {
        String input = "Hello World";
        String result = MiniEscape.escape(input);
        bh.consume(result);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void miniEscapeComplex(Blackhole bh) {
        String input = "<red>Hello</red> {player} [test] (value) #tag @mention";
        String result = MiniEscape.escape(input);
        bh.consume(result);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void miniEscapeLongString(Blackhole bh) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("<color>Text ").append(i).append("</color> ");
        }
        String input = sb.toString();
        String result = MiniEscape.escape(input);
        bh.consume(result);
    }

    // ============================================================
    // iFlow Routing Benchmarks
    // ============================================================

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void routingSimple(Blackhole bh) {
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testSender)
            .direction(Direction.others())
            .channel("chat.global")
            .text("Hello world")
            .build();

        var outcome = router.route(message, testRecipient);
        bh.consume(outcome.decision());
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void routingWithPermissionCheck(Blackhole bh) {
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testSender)
            .direction(Direction.others())
            .channel("staff.chat")
            .text("Staff message")
            .build();

        var outcome = router.route(message, testRecipient);
        bh.consume(outcome.decision());
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void routingWithRateLimit(Blackhole bh) {
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testSender)
            .direction(Direction.others())
            .channel("chat.global")
            .text("Rate limited message")
            .build();

        var outcome = router.route(message, testRecipient);
        bh.consume(outcome.decision());
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void routingComplexRules(Blackhole bh) {
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testSender)
            .direction(Direction.others())
            .channel("chat.global")
            .text("Complex routing test with multiple conditions")
            .build();

        var outcome = router.route(message, testRecipient);
        bh.consume(outcome.decision());
    }

    // ============================================================
    // Rate Limiter Benchmarks
    // ============================================================

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void rateLimiterAcquire(Blackhole bh) {
        String key = "test-channel\u0000" + UUID.randomUUID();
        boolean acquired = rateLimiter.tryAcquire(key, 1000);
        bh.consume(acquired);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void rateLimiterAcquireContended(Blackhole bh) {
        String key = "test-channel\u0000shared-key";
        boolean acquired = rateLimiter.tryAcquire(key, 1000);
        bh.consume(acquired);
    }

    // ============================================================
    // Message Dispatcher Benchmarks
    // ============================================================

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void dispatcherSingleRecipient(Blackhole bh) {
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testSender)
            .direction(Direction.others())
            .channel("chat.global")
            .text("Test message")
            .build();

        var report = dispatcher.dispatch(message);
        bh.consume(report);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void dispatcherMultipleRecipients(Blackhole bh) {
        // Create multiple recipients
        var recipients = new java.util.ArrayList<me.majhrs16.suite.api.message.Actor>();
        for (int i = 0; i < 50; i++) {
            recipients.add(new me.majhrs16.suite.api.message.Actor(
                UUID.randomUUID(), "Player" + i, 
                me.majhrs16.suite.api.message.Actor.ActorKind.PLAYER, 
                Language.EN, null));
        }

        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testSender)
            .direction(me.majhrs16.suite.api.message.Direction.all())
            .channel("chat.global")
            .text("Broadcast message")
            .build();

        var report = dispatcher.dispatch(message);
        bh.consume(report);
    }

    // ============================================================
    // Config Loading Benchmarks
    // ============================================================

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void configLoadYaml(Blackhole bh) throws Exception {
        String yaml = """
            quick-look: true
            general:
              language: en
            iflow:
              engine:
                parallel: true
            sonido:
              enabled: true
            """;

        Path tempDir = Files.createTempDirectory("benchmark-config");
        Files.writeString(tempDir.resolve("config.yml"), yaml);
        ConfigLoader.LoadResult<HostConfig> result = ConfigLoader.loadConfig(tempDir, new PluginLogger() {
            @Override public void info(String m, Object... a) {}
            @Override public void warn(String m, Object... a) {}
            @Override public void error(String m, Object... a) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m, Object... a) {}
        });
        bh.consume(result.config());
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void channelLoadYaml(Blackhole bh) {
        var channel = me.majhrs16.suite.textformatter.channel.Channel.builder("test.channel")
            .permission("test.permission")
            .type(me.majhrs16.suite.textformatter.channel.Channel.Type.CHAT)
            .showSender(true)
            .rateLimitPerSecond(10)
            .langSource(me.majhrs16.suite.api.message.Language.AUTO)
            .langTarget(me.majhrs16.suite.api.message.Language.AUTO)
            .messages(new me.majhrs16.suite.api.message.Formats(
                new String[0], new String[]{"<green>%content%</green>"}))
            .sounds(List.of(new me.majhrs16.suite.api.message.SoundSpec("test.mp3", 1.0f, 1.0f)))
            .build();
        bh.consume(channel);
    }

    // ============================================================
    // Concurrent Access Benchmarks
    // ============================================================

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Threads(4)
    public void concurrentMessageDispatch(Blackhole bh) {
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testSender)
            .direction(Direction.others())
            .channel("chat.global")
            .text("Concurrent test")
            .build();

        var report = dispatcher.dispatch(message);
        bh.consume(report);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Threads(8)
    public void concurrentRateLimiter(Blackhole bh) {
        String key = "test-channel\u0000" + Thread.currentThread().getId();
        boolean acquired = rateLimiter.tryAcquire(key, 1000);
        bh.consume(acquired);
    }
}
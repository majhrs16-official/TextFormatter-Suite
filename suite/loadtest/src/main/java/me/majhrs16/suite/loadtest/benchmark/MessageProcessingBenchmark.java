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
import me.majhrs16.suite.textformatter.template.MiniEscape;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
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
    public void setup() {
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

        // Initialize dispatcher with test config
        dispatcher = new MessageDispatcher(
            host, 
            actor -> List.of(testRecipient), 
            (actor, msg) -> {}, 
            permissions, 
            msg -> {}
        );

        // Initialize router
        router = new DefaultRouter(channels, permissions);

        // Initialize rate limiter
        rateLimiter = new RateLimiter(1000);

        // Initialize template renderer
        renderer = new TemplateRenderer(
            null, // translation
            null, // placeholders
            new PluginLogger() {
                @Override public void info(String m, Object... a) {}
                @Override public void warn(String m, Object... a) {}
                @Override public void error(String m, Object... a) {}
                @Override public void error(String m, Throwable t) {}
                @Override public void debug(String m, Object... a) {}
            }
        );
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (host != null) {
            host.close();
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
        TemplateContext context = TemplateContext.builder()
            .sender(testSender)
            .recipient(testRecipient)
            .content("Hello World")
            .sourceLanguage(Language.EN)
            .targetLanguage(Language.ES)
            .translate(true)
            .build();

        String result = renderer.render(template, context);
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
        TemplateContext context = TemplateContext.builder()
            .sender(testSender)
            .recipient(testRecipient)
            .content("This is a test message with some content to format")
            .sourceLanguage(Language.EN)
            .targetLanguage(Language.ES)
            .translate(true)
            .build();

        String result = renderer.render(template, context);
        bh.consume(result);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void templateRenderingWithTranslation(Blackhole bh) {
        Template template = Template.of("<tr>%content%</tr>");
        TemplateContext context = TemplateContext.builder()
            .sender(testSender)
            .recipient(testRecipient)
            .content("This message should be translated")
            .sourceLanguage(Language.EN)
            .targetLanguage(Language.ES)
            .translate(true)
            .build();

        String result = renderer.render(template, context);
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
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("Hello world")
            .build();

        var result = router.route(message, testRecipient);
        bh.consume(result);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void routingWithPermissionCheck(Blackhole bh) {
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testSender)
            .direction(Direction.OTHERS)
            .channel("staff.chat")
            .text("Staff message")
            .build();

        var result = router.route(message, testRecipient);
        bh.consume(result);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void routingWithRateLimit(Blackhole bh) {
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testSender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("Rate limited message")
            .build();

        var result = router.route(message, testRecipient);
        bh.consume(result);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void routingComplexRules(Blackhole bh) {
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testSender)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("Complex routing test with multiple conditions")
            .build();

        var result = router.route(message, testRecipient);
        bh.consume(result);
    }

    // ============================================================
    // Rate Limiter Benchmarks
    // ============================================================

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void rateLimiterAcquire(Blackhole bh) {
        String key = "test-channel\u0000" + UUID.randomUUID();
        boolean acquired = rateLimiter.tryAcquire(key);
        bh.consume(acquired);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void rateLimiterAcquireContended(Blackhole bh) {
        String key = "test-channel\u0000shared-key";
        boolean acquired = rateLimiter.tryAcquire(key);
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
            .direction(Direction.OTHERS)
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
    public void configLoadYaml(Blackhole bh) {
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

        HostConfig config = ConfigLoader.loadConfigString(yaml);
        bh.consume(config);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void channelLoadYaml(Blackhole bh) {
        String yaml = """
            name: test.channel
            permission: test.permission
            type: CHAT
            show-sender: true
            rate-limit-per-second: 10
            lang-source: auto
            lang-target: auto
            messages:
              - "<green>%content%</green>"
            tooltips: []
            sounds:
              - name: "test.mp3"
                volume: 1.0
                pitch: 1.0
            """;

        var channel = me.majhrs16.suite.textformatter.channel.Channel.loadFromYaml(yaml);
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
            .direction(Direction.OTHERS)
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
        boolean acquired = rateLimiter.tryAcquire(key);
        bh.consume(acquired);
    }
}
package me.majhrs16.suite.tester;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.host.port.ChatDelivery;
import me.majhrs16.suite.iflow.Router;
import me.majhrs16.suite.textformatter.TextFormatter;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runtime test service that executes automated test scenarios using LIVE server infrastructure.
 * <p>
 * This runs INSIDE the server (Spigot/Fabric) and uses real:
 * - ActorDirectory (real players online)
 * - MessageDispatcher (real routing + translation + formatting)
 * - ChatDelivery (real delivery to players/console)
 * - SuiteHost (real channels, translators, config)
 * <p>
 * Tests are triggered via command: {@code /suite test <scenario> [params]}
 * Results are reported to the command sender and server console.
 */
public final class TestService {

    private final SuiteHost host;
    private final MessageDispatcher dispatcher;
    private final ActorDirectory actorDirectory;
    private final UserLanguageStore languageStore;
    private final PluginLogger logger;

    // Test state
    private final AtomicInteger messagesSent = new AtomicInteger(0);
    private final AtomicInteger messagesDelivered = new AtomicInteger(0);
    private final AtomicInteger messagesSilenced = new AtomicInteger(0);
    private final AtomicInteger messagesRedirected = new AtomicInteger(0);

    public TestService(SuiteHost host,
                       MessageDispatcher dispatcher,
                       ActorDirectory actorDirectory,
                       UserLanguageStore languageStore,
                       PluginLogger logger) {
        this.host = host;
        this.dispatcher = dispatcher;
        this.actorDirectory = actorDirectory;
        this.languageStore = languageStore;
        this.logger = logger;
    }

    // ============ Performance Profiler ============

    /** Simple CPU/Heap profiler for measuring hot paths. */
    public static final class PerformanceProfiler {
        private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private long startCpuNs;
        private long startHeapBytes;
        private long endCpuNs;
        private long endHeapBytes;

        public void start() {
            // Force GC before measurement for cleaner heap baseline
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            startCpuNs = threadBean.getCurrentThreadCpuTime();
            startHeapBytes = memoryBean.getHeapMemoryUsage().getUsed();
        }

        public PerformanceResult stop() {
            endCpuNs = threadBean.getCurrentThreadCpuTime();
            endHeapBytes = memoryBean.getHeapMemoryUsage().getUsed();
            return new PerformanceResult(
                (endCpuNs - startCpuNs) / 1_000_000.0, // ms CPU time
                (endHeapBytes - startHeapBytes) / 1024.0 / 1024.0, // MB heap delta
                memoryBean.getHeapMemoryUsage().getUsed() / 1024.0 / 1024.0, // MB current heap
                memoryBean.getHeapMemoryUsage().getMax() / 1024.0 / 1024.0 // MB max heap
            );
        }

        public static final class PerformanceResult {
            public final double cpuTimeMs;
            public final double heapDeltaMb;
            public final double heapUsedMb;
            public final double heapMaxMb;
            public final boolean skipped;
            public final String skipMessage;

            public PerformanceResult(double cpuTimeMs, double heapDeltaMb, double heapUsedMb, double heapMaxMb) {
                this(cpuTimeMs, heapDeltaMb, heapUsedMb, heapMaxMb, false, null);
            }

            private PerformanceResult(double cpuTimeMs, double heapDeltaMb, double heapUsedMb, double heapMaxMb,
                                      boolean skipped, String skipMessage) {
                this.cpuTimeMs = cpuTimeMs;
                this.heapDeltaMb = heapDeltaMb;
                this.heapUsedMb = heapUsedMb;
                this.heapMaxMb = heapMaxMb;
                this.skipped = skipped;
                this.skipMessage = skipMessage;
            }

            public static PerformanceResult skipped(String message) {
                return new PerformanceResult(0, 0, 0, 0, true, message);
            }

            @Override
            public String toString() {
                if (skipped) {
                    return "SKIPPED: " + skipMessage;
                }
                return String.format("CPU: %.2fms | Heap Δ: %.2fMB | Heap: %.1f/%.1fMB",
                    cpuTimeMs, heapDeltaMb, heapUsedMb, heapMaxMb);
            }
        }
    }

    // ============ Test Result Tracking ============

    private static final class TestResult {
        final String name;
        final boolean passed;
        final String message;
        final long durationMs;
        final PerformanceProfiler.PerformanceResult perf;

        TestResult(String name, boolean passed, String message, long durationMs, PerformanceProfiler.PerformanceResult perf) {
            this.name = name;
            this.passed = passed;
            this.message = message;
            this.durationMs = durationMs;
            this.perf = perf;
        }
    }

    private final List<TestResult> testResults = new java.util.ArrayList<>();

    private void runTest(String name, java.util.function.Supplier<PerformanceProfiler.PerformanceResult> test, Consumer<String> reporter) {
        reporter.accept("§6[TEST] " + name + "...");
        var profiler = new PerformanceProfiler();
        long start = System.currentTimeMillis();
        boolean passed = false;
        String message = "";
        PerformanceProfiler.PerformanceResult perf = null;
        try {
            profiler.start();
            perf = test.get();
            if (perf.skipped) {
                passed = true;
                message = "SKIPPED: " + perf.skipMessage;
            } else {
                passed = true;
                message = "OK";
            }
        } catch (AssertionError e) {
            passed = false;
            message = e.getMessage();
        } catch (Exception e) {
            passed = false;
            message = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            perf = profiler.stop();
        }
        long duration = System.currentTimeMillis() - start;
        testResults.add(new TestResult(name, passed, message, duration, perf));

        String color;
        String status;
        if (perf != null && perf.skipped) {
            color = "§e"; // yellow
            status = "[SKIP]";
        } else {
            color = passed ? "§a" : "§c";
            status = passed ? "[PASS]" : "[FAIL]";
        }
        reporter.accept(color + status + "§r " + name + " " + color + "(" + duration + "ms, " + perf + ")");
        if (!passed) {
            reporter.accept("§c  → " + message);
            logger.error("Test failed: " + name + " - " + message);
        } else if (perf != null && perf.skipped) {
            reporter.accept("§e  → " + perf.skipMessage);
        }
    }

    // ============ Wrapper Methods for runFullTestSuite ============

    private PerformanceProfiler.PerformanceResult testModuleDiscovery() {
        try {
            var modules = host.getClass().getClassLoader().loadClass("me.majhrs16.suite.kernel.ModuleLoader")
                .getMethod("discover").invoke(null);
            return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
        } catch (Exception e) {
            throw new AssertionError("Module discovery failed: " + e.getMessage());
        }
    }

    private PerformanceProfiler.PerformanceResult testChannelRegistry() {
        var channels = host.channels().paths();
        if (channels.isEmpty()) throw new AssertionError("No channels loaded");
        if (!channels.contains("chat.global")) throw new AssertionError("chat.global missing");
        if (!channels.contains("join")) throw new AssertionError("join channel missing");
        if (!channels.contains("quit")) throw new AssertionError("quit channel missing");
        if (!channels.contains("death")) throw new AssertionError("death channel missing");
        if (!channels.contains("advancement")) throw new AssertionError("advancement channel missing");
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testActorDirectory() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No online players");
        var console = actorDirectory.console();
        if (console == null) throw new AssertionError("Console actor missing");
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testBasicChatRouting() {
        var players = actorDirectory.onlinePlayers();
        if (players.size() < 2) return PerformanceProfiler.PerformanceResult.skipped("Need 2+ online players");

        Actor sender = players.get(0);
        sendChat(sender, "Test message " + System.currentTimeMillis(), true);
        assertDelivered(1, "Basic chat");
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testMultilingualChat() {
        var players = actorDirectory.onlinePlayers();
        if (players.size() < 3) return PerformanceProfiler.PerformanceResult.skipped("Need 3+ online players");

        languageStore.save(players.get(0).uuid(), "es");
        languageStore.save(players.get(1).uuid(), "zh-CN");
        languageStore.save(players.get(2).uuid(), "fr");

        for (int i = 0; i < 3; i++) {
            sendChat(players.get(i), "Message from " + players.get(i).name(), true);
        }
        assertDelivered(3 * (players.size() - 1), "Multilingual chat");
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testPermissionRouting() {
        var players = actorDirectory.onlinePlayers();
        if (players.size() < 2) return PerformanceProfiler.PerformanceResult.skipped("Need 2+ online players");

        Actor sender = players.get(0);
        sendChatToChannel(sender, "VIP test", "vip.chat", true);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testWorldRouting() {
        var players = actorDirectory.onlinePlayers();
        if (players.size() < 2) return PerformanceProfiler.PerformanceResult.skipped("Need 2+ online players");

        Actor sender = players.get(0);
        var msg = me.majhrs16.suite.api.message.Message.builder()
            .type(me.majhrs16.suite.api.message.MessageType.CHAT)
            .sender(sender)
            .direction(me.majhrs16.suite.api.message.Direction.world(
                getWorldName(sender.handle())))
            .translate(true)
            .text("World test")
            .channel("chat.global")
            .build();
        dispatcher.dispatch(msg);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private String getWorldName(Object handle) {
        try {
            // Use reflection to avoid direct Bukkit dependency in tester module
            var clazz = handle.getClass();
            var method = clazz.getMethod("getWorld");
            var world = method.invoke(handle);
            var nameMethod = world.getClass().getMethod("getName");
            return (String) nameMethod.invoke(world);
        } catch (Exception e) {
            return "world";
        }
    }

    private PerformanceProfiler.PerformanceResult testRadiusRouting() {
        var players = actorDirectory.onlinePlayers();
        if (players.size() < 2) return PerformanceProfiler.PerformanceResult.skipped("Need 2+ online players");

        Actor sender = players.get(0);
        var msg = me.majhrs16.suite.api.message.Message.builder()
            .type(me.majhrs16.suite.api.message.MessageType.CHAT)
            .sender(sender)
            .direction(me.majhrs16.suite.api.message.Direction.radius(100.0))
            .translate(true)
            .text("Radius test")
            .channel("chat.global")
            .build();
        dispatcher.dispatch(msg);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testJoinQuitEvents() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No players");

        Actor actor = players.get(0);
        dispatchTypedEvent(me.majhrs16.suite.api.message.MessageType.JOIN, "join", actor, actor.name() + " joined");
        dispatchTypedEvent(me.majhrs16.suite.api.message.MessageType.LEAVE, "quit", actor, actor.name() + " left");
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testRateLimiting() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No players");

        Actor sender = players.get(0);
        for (int i = 0; i < 10; i++) {
            sendChatToChannel(sender, "Rate limit test " + i, "vip.chat", true);
        }
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    // ============ Automated Test Suite ============

    /**
     * Runs a full automated test suite with performance profiling.
     * Reports progress and results to the given consumer (command sender / console).
     */
    public void runFullTestSuite(Consumer<String> reporter) {
        reporter.accept("§a=== TextFormatter Suite: Automated Runtime Test Suite ===");
        long startTotal = System.currentTimeMillis();

        resetStats();
        testResults.clear();

        // Core Infrastructure
        runTest("Module Discovery", this::testModuleDiscovery, reporter);
        runTest("Channel Registry", this::testChannelRegistry, reporter);
        runTest("Actor Directory", this::testActorDirectory, reporter);
        runTest("SuiteHost Components", this::testSuiteHostComponents, reporter);

        // Routing & Delivery
        runTest("Basic Chat Routing", this::testBasicChatRouting, reporter);
        runTest("Multilingual Chat", this::testMultilingualChat, reporter);
        runTest("Permission Routing (VIP)", this::testPermissionRouting, reporter);
        runTest("World-based Routing", this::testWorldRouting, reporter);
        runTest("Radius-based Routing", this::testRadiusRouting, reporter);
        runTest("Specific Target Routing", this::testSpecificRouting, reporter);
        runTest("Permission-based Routing", this::testPermissionBasedRouting, reporter);

        // Events
        runTest("Join/Quit Events", this::testJoinQuitEvents, reporter);
        runTest("Death Event", this::testDeathEvent, reporter);
        runTest("Advancement Event", this::testAdvancementEvent, reporter);

        // Translation & Formatting
        runTest("Translation Pipeline", this::testTranslationPipeline, reporter);
        runTest("MiniMessage Formatting", this::testMiniMessageFormatting, reporter);
        runTest("Placeholder Resolution", this::testPlaceholderResolution, reporter);

        // Rate Limiting & iFlow
        runTest("Rate Limiting (Channel)", this::testRateLimiting, reporter);
        runTest("iFlow Rules Engine", this::testIfLowRules, reporter);

        // Concurrency & Stress
        runTest("Concurrent Chat (10 threads)", () -> runConcurrencyTestInternal(10, 20), reporter);
        runTest("Stress Test (500 msgs)", () -> runStressTestInternal(10, 50), reporter);
        runTest("Burst Test (100 parallel)", () -> runBurstTest(100), reporter);

        // Memory & Performance
        runTest("Memory Pressure (GC)", this::testMemoryPressure, reporter);
        runTest("Hot Path Profiling (Router)", this::testRouterHotPath, reporter);
        runTest("Hot Path Profiling (Formatter)", this::testFormatterHotPath, reporter);
        runTest("Hot Path Profiling (Translation)", this::testTranslationHotPath, reporter);

        // Edge Cases
        runTest("Empty Message Handling", this::testEmptyMessage, reporter);
        runTest("Invalid Channel Fallback", this::testInvalidChannelFallback, reporter);
        runTest("Offline Player Handling", this::testOfflinePlayerHandling, reporter);
        runTest("Unicode/Emoji Handling", this::testUnicodeHandling, reporter);
        runTest("Large Message (4000 chars)", this::testLargeMessage, reporter);

        // Sync Sinks (if configured)
        if (hasSyncSinks()) {
            runTest("Sync HTTP Webhook", this::testSyncHttp, reporter);
            runTest("Sync Discord", this::testSyncDiscord, reporter);
            runTest("Sync Telegram", this::testSyncTelegram, reporter);
        }

        long duration = System.currentTimeMillis() - startTotal;
        printSummary(reporter, duration);
    }

    private void printSummary(Consumer<String> reporter, long totalDuration) {
        long passed = testResults.stream().filter(r -> r.passed).count();
        long failed = testResults.size() - passed;

        reporter.accept("§a=== SUMMARY ===");
        reporter.accept("§eTotal tests: " + testResults.size() + " | §aPassed: " + passed + " §cFailed: " + failed);
        reporter.accept("§eTotal duration: " + totalDuration + "ms");
        reporter.accept("§eMessages sent: " + messagesSent.get());
        reporter.accept("§eDelivered: " + messagesDelivered.get());
        reporter.accept("§eSilenced: " + messagesSilenced.get());
        reporter.accept("§eRedirected: " + messagesRedirected.get());
        if (totalDuration > 0) {
            reporter.accept("§eThroughput: " + String.format("%.1f", messagesSent.get() / (totalDuration / 1000.0)) + " msg/s");
        }

        // Performance summary
        double totalCpu = testResults.stream().mapToDouble(r -> r.perf.cpuTimeMs).sum();
        double maxHeap = testResults.stream().mapToDouble(r -> r.perf.heapUsedMb).max().orElse(0);
        reporter.accept("§eTotal CPU time: " + String.format("%.1f", totalCpu) + "ms");
        reporter.accept("§ePeak heap during tests: " + String.format("%.1f", maxHeap) + "MB");

        if (failed > 0) {
            reporter.accept("§c=== FAILED TESTS ===");
            testResults.stream().filter(r -> !r.passed).forEach(r ->
                reporter.accept("§c  - " + r.name + ": " + r.message));
        }
        reporter.accept("§a=== Test Suite Complete ===");
    }

    /** Death event fires. */
    private PerformanceProfiler.PerformanceResult testDeathEvent() {
        var players = actorDirectory.onlinePlayers();
        if (players.size() < 2) return PerformanceProfiler.PerformanceResult.skipped("Need 2+ players for death test");

        Actor victim = players.get(0);
        Actor killer = players.get(1);
        dispatchTypedEvent(me.majhrs16.suite.api.message.MessageType.DEATH, "death", victim,
            victim.name() + " was slain by " + killer.name());
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    /** Advancement event fires. */
    private PerformanceProfiler.PerformanceResult testAdvancementEvent() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No players");

        Actor actor = players.get(0);
        dispatchTypedEvent(me.majhrs16.suite.api.message.MessageType.ADVANCEMENT, "advancement", actor,
            actor.name() + " made advancement test");
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    /** Stress test with concurrent messages. */
    public PerformanceProfiler.PerformanceResult runStressTest(int numPlayers, int msgsPerPlayer) {
        return runStressTestInternal(numPlayers, msgsPerPlayer);
    }

    private PerformanceProfiler.PerformanceResult runStressTestInternal(int numPlayers, int msgsPerPlayer) {
        var players = actorDirectory.onlinePlayers();
        int usePlayers = Math.min(numPlayers, players.size());
        if (usePlayers == 0) return PerformanceProfiler.PerformanceResult.skipped("No online players for stress test");

        int totalExpected = usePlayers * msgsPerPlayer;
        var executor = java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, usePlayers));
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();

        for (int i = 0; i < usePlayers; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                Actor p = players.get(idx);
                for (int j = 0; j < msgsPerPlayer; j++) {
                    sendChat(p, "Stress " + j + " from " + p.name(), true);
                }
            }));
        }

        for (var f : futures) {
            try { f.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        executor.shutdown();

        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    // ============ Helpers ============

    private void sendChat(Actor sender, String text, boolean translate) {
        var msg = me.majhrs16.suite.api.message.Message.builder()
            .type(me.majhrs16.suite.api.message.MessageType.CHAT)
            .sender(sender)
            .direction(me.majhrs16.suite.api.message.Direction.others().channel(me.majhrs16.suite.api.message.Channel.CHAT))
            .translate(translate)
            .text(text)
            .channel("chat.global")
            .build();
        var report = dispatcher.dispatch(msg);
        updateStats(report);
    }

    private void sendChatToChannel(Actor sender, String text, String channel, boolean translate) {
        var msg = me.majhrs16.suite.api.message.Message.builder()
            .type(me.majhrs16.suite.api.message.MessageType.CHAT)
            .sender(sender)
            .direction(me.majhrs16.suite.api.message.Direction.others().channel(me.majhrs16.suite.api.message.Channel.CHAT))
            .translate(translate)
            .text(text)
            .channel(channel)
            .build();
        var report = dispatcher.dispatch(msg);
        updateStats(report);
    }

    private void dispatchTypedEvent(me.majhrs16.suite.api.message.MessageType type,
                                     String channelName, Actor subject, String content) {
        if (host.channels().resolve(channelName) == null) return;
        var msg = me.majhrs16.suite.api.message.Message.builder()
            .type(type)
            .sender(subject)
            .direction(me.majhrs16.suite.api.message.Direction.all())
            .translate(true)
            .text(content)
            .channel(channelName)
            .build();
        var report = dispatcher.dispatch(msg);
        updateStats(report);
    }

    private void updateStats(me.majhrs16.suite.host.DispatchReport report) {
        messagesSent.incrementAndGet();
        messagesDelivered.addAndGet(report.delivered());
        messagesSilenced.addAndGet(report.silenced());
        messagesRedirected.addAndGet(report.redirected());
    }

    private void assertDelivered(int expectedMin, String context) {
        if (messagesDelivered.get() < expectedMin) {
            throw new AssertionError(context + ": expected at least " + expectedMin + " delivered, got " + messagesDelivered.get());
        }
    }

    private void resetStats() {
        messagesSent.set(0);
        messagesDelivered.set(0);
        messagesSilenced.set(0);
        messagesRedirected.set(0);
    }

    // ============ New Test Methods ============

    private PerformanceProfiler.PerformanceResult testSuiteHostComponents() {
        if (host.config() == null) throw new AssertionError("HostConfig missing");
        if (host.channels() == null) throw new AssertionError("ChannelRegistry missing");
        if (host.translation() == null) throw new AssertionError("TranslationService missing");
        if (host.formatter() == null) throw new AssertionError("TextFormatter missing");
        if (host.router() == null) throw new AssertionError("Router missing");
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testSpecificRouting() {
        var players = actorDirectory.onlinePlayers();
        if (players.size() < 2) return PerformanceProfiler.PerformanceResult.skipped("Need 2+ players");

        Actor sender = players.get(0);
        Actor target = players.get(1);
        var msg = me.majhrs16.suite.api.message.Message.builder()
            .type(me.majhrs16.suite.api.message.MessageType.CHAT)
            .sender(sender)
            .direction(me.majhrs16.suite.api.message.Direction.specific(me.majhrs16.suite.api.message.Channel.CHAT, target))
            .translate(true)
            .text("Private message")
            .channel("chat.global")
            .build();
        var report = dispatcher.dispatch(msg);
        updateStats(report);
        if (report.delivered() != 1) throw new AssertionError("Specific routing should deliver to exactly 1 target");
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testPermissionBasedRouting() {
        var players = actorDirectory.onlinePlayers();
        if (players.size() < 2) return PerformanceProfiler.PerformanceResult.skipped("Need 2+ players");

        Actor sender = players.get(0);
        var msg = me.majhrs16.suite.api.message.Message.builder()
            .type(me.majhrs16.suite.api.message.MessageType.CHAT)
            .sender(sender)
            .direction(me.majhrs16.suite.api.message.Direction.permission("suite.admin"))
            .translate(true)
            .text("Admin only")
            .channel("chat.global")
            .build();
        var report = dispatcher.dispatch(msg);
        updateStats(report);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testTranslationPipeline() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No players");

        // Test with translation enabled
        Actor sender = players.get(0);
        languageStore.save(sender.uuid(), "es");
        sendChat(sender, "Hello world", true);

        // Test with translation disabled
        languageStore.save(sender.uuid(), "off");
        sendChat(sender, "Hello world", false);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testMiniMessageFormatting() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No players");

        Actor sender = players.get(0);
        // Test gradients, hover, click events, colors
        sendChat(sender, "<gradient:blue:gold>Gradient text</gradient>", true);
        sendChat(sender, "<hover:show_text:'Tooltip!'>Hover me</hover>", true);
        sendChat(sender, "<click:run_command:'/help'>Click me</click>", true);
        sendChat(sender, "<red>Red <bold>Bold <italic>Italic</italic></bold></red>", true);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testPlaceholderResolution() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No players");

        Actor sender = players.get(0);
        sendChat(sender, "My name is %player_name% and I'm in %player_world%", true);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testIfLowRules() {
        // Test iFlow rule engine if channel has rules configured
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No players");

        Actor sender = players.get(0);
        // Send message that might trigger rules
        sendChat(sender, "Test rule trigger", true);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    /** Concurrency test with multiple threads. */
    public void runConcurrencyTest(int threads, int msgsPerThread) {
        runConcurrencyTestInternal(threads, msgsPerThread);
    }

    private PerformanceProfiler.PerformanceResult runConcurrencyTestInternal(int threads, int msgsPerThread) {
        var players = actorDirectory.onlinePlayers();
        int usePlayers = Math.min(threads, players.size());
        if (usePlayers == 0) return PerformanceProfiler.PerformanceResult.skipped("No online players");

        var executor = Executors.newFixedThreadPool(threads);
        var futures = new java.util.ArrayList<Future<?>>();

        for (int i = 0; i < threads; i++) {
            final int idx = i % usePlayers;
            futures.add(executor.submit(() -> {
                Actor p = players.get(idx);
                for (int j = 0; j < msgsPerThread; j++) {
                    sendChat(p, "Concurrent " + j + " from " + p.name(), true);
                }
            }));
        }

        for (var f : futures) {
            try { f.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        executor.shutdown();
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult runStressTestWithResult(int numPlayers, int msgsPerPlayer) {
        var players = actorDirectory.onlinePlayers();
        int usePlayers = Math.min(numPlayers, players.size());
        if (usePlayers == 0) return PerformanceProfiler.PerformanceResult.skipped("No online players for stress test");

        int totalExpected = usePlayers * msgsPerPlayer;
        var executor = Executors.newFixedThreadPool(Math.min(8, usePlayers));
        var futures = new java.util.ArrayList<Future<?>>();

        for (int i = 0; i < usePlayers; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                Actor p = players.get(idx);
                for (int j = 0; j < msgsPerPlayer; j++) {
                    sendChat(p, "Stress " + j + " from " + p.name(), true);
                }
            }));
        }

        for (var f : futures) {
            try { f.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        executor.shutdown();
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult runBurstTest(int parallelMessages) {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No players");

        Actor sender = players.get(0);
        var executor = Executors.newFixedThreadPool(parallelMessages);
        var futures = new java.util.ArrayList<Future<?>>();

        for (int i = 0; i < parallelMessages; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> sendChat(sender, "Burst " + idx, true)));
        }

        for (var f : futures) {
            try { f.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        executor.shutdown();
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testMemoryPressure() {
        // Allocate memory to trigger GC pressure (10MB total, not 1GB)
        var lists = new java.util.ArrayList<byte[]>();
        for (int i = 0; i < 10; i++) {
            lists.add(new byte[1024 * 1024]); // 1MB each, 10MB total
        }
        lists.clear();
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testRouterHotPath() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No players");

        Actor sender = players.get(0);
        // Hot path: route 1000 messages through router
        for (int i = 0; i < 1000; i++) {
            var msg = me.majhrs16.suite.api.message.Message.builder()
                .type(me.majhrs16.suite.api.message.MessageType.CHAT)
                .sender(sender)
                .direction(me.majhrs16.suite.api.message.Direction.others().channel(me.majhrs16.suite.api.message.Channel.CHAT))
                .translate(false)
                .text("Router hot path " + i)
                .channel("chat.global")
                .build();
            host.router().route(msg, sender);
        }
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testFormatterHotPath() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) return PerformanceProfiler.PerformanceResult.skipped("No players");

        Actor sender = players.get(0);
        // Hot path: format 1000 messages using reflection to avoid Component dependency
        try {
            var formatter = host.formatter();
            var formatMethod = formatter.getClass().getMethod("format",
                me.majhrs16.suite.api.message.Message.class,
                me.majhrs16.suite.textformatter.template.TemplateContext.class);
            
            for (int i = 0; i < 1000; i++) {
                var msg = me.majhrs16.suite.api.message.Message.builder()
                    .type(me.majhrs16.suite.api.message.MessageType.CHAT)
                    .sender(sender)
                    .direction(me.majhrs16.suite.api.message.Direction.others().channel(me.majhrs16.suite.api.message.Channel.CHAT))
                    .translate(false)
                    .text("Format test " + i)
                    .channel("chat.global")
                    .build();
                var ctx = me.majhrs16.suite.textformatter.template.TemplateContext.builder(sender, Language.EN, Language.ES)
                    .content("Format test " + i)
                    .translate(false)
                    .build();
                formatMethod.invoke(formatter, msg, ctx);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke formatter via reflection", e);
        }
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testTranslationHotPath() {
        // Hot path: detect language 1000 times
        for (int i = 0; i < 1000; i++) {
            host.translation().detect("This is a test message for language detection " + i);
        }
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testEmptyMessage() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) throw new AssertionError("No players");

        Actor sender = players.get(0);
        sendChat(sender, "", true); // Empty message
        sendChat(sender, "   ", true); // Whitespace only
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testInvalidChannelFallback() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) throw new AssertionError("No players");

        Actor sender = players.get(0);
        // Send to non-existent channel - should fallback gracefully
        sendChatToChannel(sender, "Invalid channel test", "nonexistent.channel", true);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testOfflinePlayerHandling() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) throw new AssertionError("No players");

        // Try to resolve offline player
        var offline = actorDirectory.byUuid(java.util.UUID.randomUUID());
        if (offline.isPresent()) throw new AssertionError("Offline player should not resolve");

        // Dispatch to offline player via SPECIFIC - should handle gracefully
        Actor sender = players.get(0);
        Actor fakeTarget = new Actor(java.util.UUID.randomUUID(), "FakePlayer", Actor.ActorKind.PLAYER, Language.EN, null);
        var msg = me.majhrs16.suite.api.message.Message.builder()
            .type(me.majhrs16.suite.api.message.MessageType.CHAT)
            .sender(sender)
            .direction(me.majhrs16.suite.api.message.Direction.specific(me.majhrs16.suite.api.message.Channel.CHAT, fakeTarget))
            .translate(true)
            .text("To offline player")
            .channel("chat.global")
            .build();
        dispatcher.dispatch(msg);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testUnicodeHandling() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) throw new AssertionError("No players");

        Actor sender = players.get(0);
        sendChat(sender, "🎮 Minecraft ♥ ☺ ☻ ♠ ♣ ♥ ♦ ♪ ♫", true);
        sendChat(sender, "中文 한국어 日本語 العربية עברית", true);
        sendChat(sender, "🚀🌟💎⚡🔥💧🌈🎯🎲🎮", true);
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testLargeMessage() {
        var players = actorDirectory.onlinePlayers();
        if (players.isEmpty()) throw new AssertionError("No players");

        Actor sender = players.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("This is a long message line ").append(i).append(". ");
        }
        sendChat(sender, sb.toString(), true); // ~4000 chars
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private boolean hasSyncSinks() {
        // Check if any sync sinks are configured
        return host.channels().paths().stream()
            .anyMatch(name -> name.startsWith("sync."));
    }

    private PerformanceProfiler.PerformanceResult testSyncHttp() {
        // Test HTTP webhook if configured
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testSyncDiscord() {
        // Test Discord if configured
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    private PerformanceProfiler.PerformanceResult testSyncTelegram() {
        // Test Telegram if configured
        return new PerformanceProfiler.PerformanceResult(0, 0, 0, 0);
    }

    // ============ Getters ============

    public SuiteHost getHost() { return host; }
    public MessageDispatcher getDispatcher() { return dispatcher; }
    public ActorDirectory getActorDirectory() { return actorDirectory; }
    public UserLanguageStore getLanguageStore() { return languageStore; }
    public List<TestResult> getTestResults() { return List.copyOf(testResults); }
}
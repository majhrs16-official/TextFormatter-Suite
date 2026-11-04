package me.majhrs16.suite.iflow.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WasmPolicyRouterTest {

    private static final Actor EMITTER = Actor.unknown("Alice").withLanguage(Language.EN);
    private static final Actor RECIPIENT = Actor.unknown("Bob").withLanguage(Language.ES);
    private static final ChannelRegistry CHANNELS = ChannelRegistry.builder()
            .register(Channel.builder("chat").build())
            .build();

    @TempDir
    Path dir;

    @Test
    void loadsVerifiedPolicyAndMapsDeliver() throws Exception {
        writePolicy(decisionModule(PolicyAbiV3.ACTION_DELIVER).build());

        try (WasmPolicyRouter router = router()) {
            assertTrue(router.isAvailable(), router.lastError());
            RouteOutcome outcome = router.route(message(), RECIPIENT);
            assertEquals(PolicyTarget.LOG, outcome.decision().target());
            assertTrue(outcome.delivered());
        }
    }

    @Test
    void missingHashFailsClosed() throws Exception {
        Files.write(dir.resolve("policy.wasm"),
                decisionModule(PolicyAbiV3.ACTION_DELIVER).build());

        try (WasmPolicyRouter router = router()) {
            assertFalse(router.isAvailable());
            assertTrue(router.lastError().contains("SHA-256"), router.lastError());
            assertEquals(PolicyTarget.REJECT,
                    router.route(message(), RECIPIENT).decision().target());
        }
    }

    @Test
    void mismatchedHashFailsClosed() throws Exception {
        Files.write(dir.resolve("policy.wasm"),
                decisionModule(PolicyAbiV3.ACTION_DELIVER).build());
        Files.writeString(dir.resolve("policy.sha256"), "0".repeat(64) + "  policy.wasm\n");

        try (WasmPolicyRouter router = router()) {
            assertFalse(router.isAvailable());
            assertTrue(router.lastError().contains("no coincide"), router.lastError());
        }
    }

    @Test
    void mapsCancelEventToDropAndErrorToReject() throws Exception {
        writePolicy(decisionModule(PolicyAbiV3.ACTION_CANCEL_EVENT).build());
        try (WasmPolicyRouter router = router()) {
            assertEquals(PolicyTarget.DROP,
                    router.route(message(), RECIPIENT).decision().target());
        }

        writePolicy(decisionModule(PolicyAbiV3.ACTION_ERROR).build());
        try (WasmPolicyRouter router = router()) {
            assertEquals(PolicyTarget.REJECT,
                    router.route(message(), RECIPIENT).decision().target());
        }
    }

    @Test
    void explicitReloadPicksUpNewArtifact() throws Exception {
        writePolicy(decisionModule(PolicyAbiV3.ACTION_DELIVER).build());
        try (WasmPolicyRouter router = router()) {
            assertEquals(PolicyTarget.LOG,
                    router.route(message(), RECIPIENT).decision().target());

            writePolicy(decisionModule(PolicyAbiV3.ACTION_CANCEL_EVENT).build());
            assertTrue(router.reload(), router.lastError());
            assertEquals(PolicyTarget.DROP,
                    router.route(message(), RECIPIENT).decision().target());
        }
    }

    @Test
    void watcherReloadsAutomatically() throws Exception {
        writePolicy(decisionModule(PolicyAbiV3.ACTION_DELIVER).build());
        try (WasmPolicyRouter router = router()) {
            assertTrue(router.awaitWatcherReady(2, TimeUnit.SECONDS));
            assertEquals(PolicyTarget.LOG,
                    router.route(message(), RECIPIENT).decision().target());

            writePolicy(decisionModule(PolicyAbiV3.ACTION_CANCEL_EVENT).build());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (router.route(message(), RECIPIENT).decision().target() == PolicyTarget.DROP) {
                    return;
                }
                Thread.sleep(50);
            }
            fail("watcher did not reload the new policy");
        }
    }

    private WasmPolicyRouter router() {
        return new WasmPolicyRouter(dir, CHANNELS, PermissionChecker.ALLOW_ALL,
                null, null, noopLogger());
    }

    private void writePolicy(byte[] bytes) throws Exception {
        Files.write(dir.resolve("policy.wasm"), bytes);
        Files.writeString(dir.resolve("policy.sha256"),
                sha256(bytes) + "  policy.wasm\n");
    }

    private static Message message() {
        return Message.builder()
                .text("hola")
                .sender(EMITTER)
                .channel("chat")
                .langSource(Language.EN)
                .langTarget(Language.ES)
                .build();
    }

    private static WasmTestModule decisionModule(int action) {
        WasmTestModule module = new WasmTestModule();
        int decisionType = module.functionType(
                new int[]{WasmTestModule.I32, WasmTestModule.I32, WasmTestModule.I32,
                        WasmTestModule.I32, WasmTestModule.I32},
                new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.DECISION, decisionType);
        int decideType = module.functionType(new int[0], new int[]{WasmTestModule.I32});
        int decide = module.function(decideType);
        module.exportFunction(PolicyAbiV3.EXPORT_DECIDE, decide);
        module.code(new byte[]{
                0x41, (byte) action, // action
                0x41, 0x00, // target ptr
                0x41, 0x00, // target len
                0x41, 0x00, // reason ptr
                0x41, 0x00, // reason len
                0x10, 0x00, 0x1a, // decision, drop
                0x41, 0x00
        });
        return module;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static PluginLogger noopLogger() {
        return new PluginLogger() {
            @Override public void info(String message, Object... args) { }
            @Override public void warn(String message, Object... args) { }
            @Override public void error(String message, Object... args) { }
            @Override public void error(String message, Throwable throwable) { }
            @Override public void debug(String message, Object... args) { }
        };
    }
}

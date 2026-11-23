package me.majhrs16.suite.iflow.vm.engine.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * End-to-end routing tests of the {@code iflow-32} engine.
 *
 * <p>The policy fixture reads {@code message.content} through the v3
 * {@code HOST} ABI and decides from the first byte: a text starting with
 * {@code b} is cancelled, anything else is delivered. The decision therefore
 * cannot come from a constant policy and proves that the guest observed the
 * host message for every unit, including after the cold reset between
 * messages.</p>
 */
class Iflow32RouterTest {

    @TempDir
    Path temp;

    @Test
    void decidesFromTheMessageContentOfEachUnit() throws IOException {
        Path configDir = policyDir(temp.resolve("iflow-32"));
        Iflow32Router router = newRouter(configDir);
        try {
            assertTrue(router.isAvailable(), router::lastError);

            Actor emitter = Actor.unknown("emitter");
            Actor recipient = Actor.unknown("recipient");

            Message blocked = message(emitter, "block this");
            RouteOutcome dropped = router.route(blocked, recipient);
            assertEquals(PolicyTarget.DROP, dropped.decision().target());
            assertEquals("blocked", dropped.decision().reason());
            assertEquals(blocked.id(), dropped.message().id());

            Message allowed = message(emitter, "allow this");
            RouteOutcome delivered = router.route(allowed, recipient);
            assertEquals(PolicyTarget.LOG, delivered.decision().target(),
                    delivered.decision().reason());
            assertEquals("policy", delivered.decision().reason());
            assertEquals(allowed.id(), delivered.message().id());

            // Third unit: the kernel was booted once and reused; the guest
            // still reads the new content instead of a cached decision.
            RouteOutcome droppedAgain = router.route(
                    message(emitter, "block again"), recipient);
            assertEquals(PolicyTarget.DROP, droppedAgain.decision().target());
            assertEquals("blocked", droppedAgain.decision().reason());
            assertEquals(1, router.boots(), "the kernel must boot only once");
        } finally {
            router.close();
        }
    }

    @Test
    void reportsUnavailableWhenPolicyIsMissing() throws IOException {
        Path configDir = Files.createDirectories(temp.resolve("empty"));
        Iflow32Router router = newRouter(configDir);
        try {
            assertFalse(router.isAvailable());
            assertTrue(router.lastError().contains(Iflow32Router.POLICY_FILE));
            RouteOutcome outcome = router.route(
                    message(Actor.unknown("emitter"), "allow"), Actor.unknown("recipient"));
            assertEquals(PolicyTarget.REJECT, outcome.decision().target());
            assertTrue(outcome.decision().reason().contains("unavailable"),
                    outcome.decision().reason());
        } finally {
            router.close();
        }
    }

    @Test
    void reportsUnavailableWhenPolicyIsNotAV3Rom() throws IOException {
        Path configDir = Files.createDirectories(temp.resolve("broken"));
        Files.writeString(configDir.resolve(Iflow32Router.POLICY_FILE), "not a rom");
        Iflow32Router router = newRouter(configDir);
        try {
            assertFalse(router.isAvailable());
            assertFalse(router.lastError().isBlank());
            RouteOutcome outcome = router.route(
                    message(Actor.unknown("emitter"), "allow"), Actor.unknown("recipient"));
            assertEquals(PolicyTarget.REJECT, outcome.decision().target());
        } finally {
            router.close();
        }
    }

    private Iflow32Router newRouter(Path configDir) {
        return new Iflow32Router(configDir, ChannelRegistry.empty(),
                PermissionChecker.ALLOW_ALL, NoopLogger.INSTANCE);
    }

    private static Path policyDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (InputStream rom = Iflow32RouterTest.class.getResourceAsStream(
                "/rom/v3/policy-route.rom")) {
            if (rom == null) {
                throw new IOException("missing test resource /rom/v3/policy-route.rom");
            }
            Files.copy(rom, dir.resolve(Iflow32Router.POLICY_FILE));
        }
        return dir;
    }

    private static Message message(Actor emitter, String text) {
        return Message.builder()
                .sender(emitter)
                .channel("chat")
                .text(text)
                .build();
    }

    private enum NoopLogger implements PluginLogger {
        INSTANCE;

        @Override
        public void info(String message, Object... args) {
        }

        @Override
        public void warn(String message, Object... args) {
        }

        @Override
        public void error(String message, Object... args) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }

        @Override
        public void debug(String message, Object... args) {
        }
    }
}

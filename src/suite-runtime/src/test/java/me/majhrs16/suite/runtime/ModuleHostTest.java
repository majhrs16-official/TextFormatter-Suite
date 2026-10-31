package me.majhrs16.suite.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import me.majhrs16.suite.api.runtime.ModuleActivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleDeactivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F2 gate: real module JARs are compiled, packaged and loaded from disk.
 *
 * <p>The fixtures are generated at test time so the classes only exist inside
 * the JAR. That proves activation does not depend on the test classpath.</p>
 */
class ModuleHostTest {

    @TempDir
    Path tempDir;

    @Test
    void ignoresActivatorsPublishedByTheRuntimeClasspath() throws Exception {
        Path jar = FixtureModules.buildJar(tempDir, "classpath-fixture", "1.0.0", "fixture",
                null, null, false, false);

        try (ModuleHost host = newHost()) {
            assertTrue(host.activate(jar).isSuccess(),
                    "only the activator declared by the module JAR may be loaded");
            assertEquals(List.of("classpath-fixture"), host.activeModules());
            assertEquals(List.of("fixture"),
                    host.services().findAll(CharSequence.class).stream()
                            .map(CharSequence::toString).toList());
        }
    }

    @Test
    void activatesTwoVersionsOfTheSameClassInIsolatedClassLoaders() throws Exception {
        Path v1 = FixtureModules.buildJar(tempDir, "fixture-v1", "1.0.0", "v1", null, null, false, false);
        Path v2 = FixtureModules.buildJar(tempDir, "fixture-v2", "2.0.0", "v2", null, null, false, false);

        try (ModuleHost host = newHost()) {
            assertTrue(host.activate(v1).isSuccess());
            assertTrue(host.activate(v2).isSuccess());

            List<CharSequence> payloads = host.services().findAll(CharSequence.class);
            assertEquals(2, payloads.size());
            assertEquals(Set.of("v1", "v2"),
                    payloads.stream().map(CharSequence::toString).collect(Collectors.toSet()));
            assertNotSame(payloads.get(0).getClass(), payloads.get(1).getClass(),
                    "each module class must come from its own classloader");

            assertEquals(Map.of(
                    "fixture-v1", ModuleState.ACTIVE,
                    "fixture-v2", ModuleState.ACTIVE), host.snapshot());
            assertFalse(Files.exists(tempDir.resolve("config").resolve("fixture-v1").resolve("released.marker")));

            DeactivationResult result = host.deactivate("fixture-v1");

            assertTrue(result.isClean());
            assertTrue(Files.exists(tempDir.resolve("config").resolve("fixture-v1").resolve("released.marker")),
                    "tracked resources must be released on deactivation");
            assertEquals(ModuleState.INSTALLED, host.state("fixture-v1").orElseThrow());
            assertEquals(List.of("fixture-v2"), host.activeModules());
            assertEquals(1, host.services().findAll(CharSequence.class).size(),
                    "deactivation must remove the services owned by the module");
        }
    }

    @Test
    void loadInstalledActivatesInDependencyOrder() throws Exception {
        Path base = FixtureModules.buildJar(tempDir, "z-base", "1.0.0", "base", "base", null, false, true);
        Path consumer = FixtureModules.buildJar(tempDir, "a-consumer", "1.0.0", "consumer", null, "base", false, true);

        Path packages = tempDir.resolve("library").resolve("packages");
        Files.createDirectories(packages);
        Files.copy(consumer, packages.resolve("a-consumer.jar"));
        Files.copy(base, packages.resolve("z-base.jar"));

        try (ModuleHost host = newHost()) {
            List<ActivationResult> results = host.loadInstalled();

            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(ActivationResult::isSuccess));
            assertEquals(List.of("z-base", "a-consumer"),
                    Files.readAllLines(tempDir.resolve("config").resolve("activation-order.log")));
            assertEquals(ModuleState.ACTIVE, host.state("a-consumer").orElseThrow());
        }
    }

    @Test
    void failedActivationIsReportedAndRollsBackServices() throws Exception {
        Path broken = FixtureModules.buildJar(tempDir, "broken", "1.0.0", "broken", null, null, true, false);

        try (ModuleHost host = newHost()) {
            ActivationResult result = host.activate(broken);

            assertFalse(result.isSuccess());
            assertEquals(ModuleState.FAILED, result.state());
            assertEquals(ModuleState.FAILED, host.state("broken").orElseThrow());
            assertTrue(host.activeModules().isEmpty());
            assertTrue(host.services().findAll(CharSequence.class).isEmpty());
        }
    }

    @Test
    void unsatisfiedRequirementIsNotActivated() throws Exception {
        Path orphan = FixtureModules.buildJar(tempDir, "orphan", "1.0.0", "orphan", null, "missing", false, false);

        Path packages = tempDir.resolve("library").resolve("packages");
        Files.createDirectories(packages);
        Files.copy(orphan, packages.resolve("orphan.jar"));

        try (ModuleHost host = newHost()) {
            List<ActivationResult> results = host.loadInstalled();

            assertEquals(1, results.size());
            assertFalse(results.get(0).isSuccess());
            assertEquals(ModuleState.FAILED, host.state("orphan").orElseThrow());
            assertTrue(host.activeModules().isEmpty());
        }
    }

    @Test
    void loadsClassesFromSharedDependencyJar() throws Exception {
        Path dependency = FixtureModules.buildDependencyJar(
                tempDir, "fixture.dep.Shared", "from-dependency");
        Path consumer = FixtureModules.buildJarUsingDependency(
                tempDir, "dep-consumer", "1.0.0", dependency,
                "fixture.dep.Shared", "value()");

        try (ModuleHost host = newHost()) {
            ActivationResult result = host.activate(consumer, List.of(dependency));

            assertTrue(result.isSuccess());
            assertEquals(List.of("from-dependency"),
                    host.services().findAll(CharSequence.class).stream()
                            .map(CharSequence::toString).toList());
        }
    }

    @Test
    void missingSharedDependencyFailsActivation() throws Exception {
        Path dependency = FixtureModules.buildDependencyJar(
                tempDir, "fixture.dep.Missing", "unused");
        Path consumer = FixtureModules.buildJarUsingDependency(
                tempDir, "dep-orphan", "1.0.0", dependency,
                "fixture.dep.Missing", "value()");

        try (ModuleHost host = newHost()) {
            ActivationResult result = host.activate(consumer);

            assertFalse(result.isSuccess());
            assertEquals(ModuleState.FAILED, host.state("dep-orphan").orElseThrow());
            assertTrue(host.services().findAll(CharSequence.class).isEmpty());
        }
    }

    @Test
    void publishesActivationAndDeactivationEvents() throws Exception {
        Path jar = FixtureModules.buildJar(
                tempDir, "event-fixture", "1.0.0", "event", null, null, false, false);

        try (ModuleHost host = newHost()) {
            List<String> activated = new ArrayList<>();
            List<String> deactivated = new ArrayList<>();
            host.events().subscribe(ModuleActivatedEvent.class,
                    event -> activated.add(event.moduleId()));
            host.events().subscribe(ModuleDeactivatedEvent.class,
                    event -> deactivated.add(event.moduleId()));

            assertTrue(host.activate(jar).isSuccess());
            assertEquals(List.of("event-fixture"), activated);

            assertTrue(host.deactivate("event-fixture").isClean());
            assertEquals(List.of("event-fixture"), deactivated);
        }
    }

    @Test
    void upgradesAnActiveModuleInANewClassLoaderAndClosesTheOldOne() throws Exception {
        Path v1 = FixtureModules.buildJar(tempDir, "upgrade-fixture", "1.0.0", "v1",
                null, null, false, false);
        Path v2 = FixtureModules.buildJar(tempDir, "upgrade-fixture", "2.0.0", "v2",
                null, null, false, false);

        try (ModuleHost host = newHost()) {
            assertTrue(host.activate(v1).isSuccess());
            CharSequence before = host.services().find(CharSequence.class).orElseThrow();
            ModuleClassLoader oldLoader = (ModuleClassLoader) before.getClass().getClassLoader();

            UpgradeResult result = host.upgrade("upgrade-fixture", v2, List.of());

            assertTrue(result.isSuccess(), () -> "upgrade failed: " + result.message());
            assertEquals("1.0.0", result.previousVersion());
            assertEquals("2.0.0", result.newVersion());
            assertFalse(result.rolledBack());
            assertEquals(ModuleState.ACTIVE, host.state("upgrade-fixture").orElseThrow());
            assertEquals(List.of("upgrade-fixture"), host.activeModules());

            CharSequence after = host.services().find(CharSequence.class).orElseThrow();
            assertEquals("v2", after.toString());
            assertNotSame(before.getClass(), after.getClass(),
                    "the new version must come from a different classloader");
            assertTrue(oldLoader.isClosed(),
                    "the previous classloader must be closed after a successful upgrade");
            assertTrue(Files.exists(tempDir.resolve("config")
                    .resolve("upgrade-fixture").resolve("released.marker")),
                    "the previous version must run its resource cleanup");
        }
    }

    @Test
    void loadInstalledActivatesOnlyTheHighestInstalledVersion() throws Exception {
        Path v1 = FixtureModules.buildJar(tempDir, "duplicate-fixture", "1.0.0", "v1",
                null, null, false, false);
        Path v2 = FixtureModules.buildJar(tempDir, "duplicate-fixture", "2.0.0", "v2",
                null, null, false, false);
        Path packages = tempDir.resolve("library/packages");
        Files.createDirectories(packages);
        Files.copy(v1, packages.resolve("duplicate-fixture-1.0.0.jar"));
        Files.copy(v2, packages.resolve("duplicate-fixture-2.0.0.jar"));

        try (ModuleHost host = newHost()) {
            List<ActivationResult> results = host.loadInstalled();

            assertEquals(1, results.size());
            assertTrue(results.get(0).isSuccess());
            assertEquals(List.of("duplicate-fixture"), host.activeModules());
            assertEquals("v2", host.services().find(CharSequence.class)
                    .orElseThrow().toString());
        }
    }

    @Test
    void failedUpgradeRestoresThePreviousVersion() throws Exception {
        Path v1 = FixtureModules.buildJar(tempDir, "rollback-fixture", "1.0.0", "v1",
                null, null, false, false);
        Path v2 = FixtureModules.buildJar(tempDir, "rollback-fixture", "2.0.0", "v2",
                null, null, true, false);

        try (ModuleHost host = newHost()) {
            assertTrue(host.activate(v1).isSuccess());
            CharSequence before = host.services().find(CharSequence.class).orElseThrow();
            ModuleClassLoader oldLoader = (ModuleClassLoader) before.getClass().getClassLoader();

            UpgradeResult result = host.upgrade("rollback-fixture", v2, List.of());

            assertFalse(result.isSuccess());
            assertTrue(result.rolledBack(), "the previous version must be restored");
            assertEquals(ModuleState.ACTIVE, result.state());
            assertEquals(ModuleState.ACTIVE, host.state("rollback-fixture").orElseThrow());
            assertEquals(List.of("rollback-fixture"), host.activeModules());
            assertTrue(oldLoader.isClosed());
            assertEquals("v1", host.services().find(CharSequence.class)
                    .orElseThrow().toString(),
                    "the restored module must serve the previous payload");
            assertNotNull(result.cause());
        }
    }

    @Test
    void leakedModuleThreadMarksDeactivationAsRestartRequired() throws Exception {
        Path jar = FixtureModules.buildJarLeakingThread(
                tempDir, "thread-leak-fixture", "1.0.0");

        try (ModuleHost host = newHost()) {
            assertTrue(host.activate(jar).isSuccess());

            DeactivationResult result = host.deactivate("thread-leak-fixture");

            assertFalse(result.isClean());
            assertEquals(ModuleState.RESTART_REQUIRED, result.state());
            assertNotNull(result.cause());
            assertTrue(result.cause().getMessage().contains("fixture-leak-thread-leak-fixture"),
                    () -> "unexpected cause: " + result.cause().getMessage());
            assertTrue(host.activeModules().isEmpty());
            assertTrue(host.services().findAll(CharSequence.class).isEmpty());
        } finally {
            stopLeakedThread("fixture-leak-thread-leak-fixture");
        }
    }

    @Test
    void failingResourceCleanupMarksDeactivationAsRestartRequired() throws Exception {
        Path jar = FixtureModules.buildJarFailingResourceRelease(
                tempDir, "resource-leak-fixture", "1.0.0");

        try (ModuleHost host = newHost()) {
            assertTrue(host.activate(jar).isSuccess());

            DeactivationResult result = host.deactivate("resource-leak-fixture");

            assertFalse(result.isClean());
            assertEquals(ModuleState.RESTART_REQUIRED, result.state());
            assertNotNull(result.cause());
            assertTrue(host.activeModules().isEmpty());
            assertTrue(host.services().findAll(CharSequence.class).isEmpty(),
                    "services must be withdrawn even when a resource fails to close");
        }
    }

    @Test
    void failedDeactivationStillPublishesEventAndRequiresRestart() throws Exception {
        Path jar = FixtureModules.buildJarFailingOnStop(tempDir, "stop-fixture", "1.0.0", "stop");

        try (ModuleHost host = newHost()) {
            List<String> deactivated = new ArrayList<>();
            host.events().subscribe(ModuleDeactivatedEvent.class,
                    event -> deactivated.add(event.moduleId()));

            assertTrue(host.activate(jar).isSuccess());
            assertEquals(1, host.services().findAll(CharSequence.class).size());

            DeactivationResult result = host.deactivate("stop-fixture");

            assertFalse(result.isClean(), "a failed stop() must not report a clean deactivation");
            assertEquals(ModuleState.RESTART_REQUIRED, result.state());
            assertNotNull(result.cause(), "the stop() failure must be preserved as the cause");
            assertEquals(ModuleState.RESTART_REQUIRED, host.state("stop-fixture").orElseThrow());
            assertEquals(List.of("stop-fixture"), deactivated,
                    "consumers must be notified even when the module did not release cleanly");
            assertTrue(host.activeModules().isEmpty());
            assertTrue(host.services().findAll(CharSequence.class).isEmpty(),
                    "services must be withdrawn even when stop() fails");
            assertTrue(Files.exists(tempDir.resolve("config").resolve("stop-fixture").resolve("released.marker")),
                    "tracked resources must still be released when stop() fails");
        }
    }

    private ModuleHost newHost() {
        return new ModuleHost(
                tempDir.resolve("library"),
                tempDir.resolve("config"),
                PlatformInfo.current("test", "1.0"),
                FixtureModules.logger());
    }

    private static void stopLeakedThread(String name) throws InterruptedException {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals(name)) {
                thread.interrupt();
                thread.join(2_000L);
                return;
            }
        }
    }
}

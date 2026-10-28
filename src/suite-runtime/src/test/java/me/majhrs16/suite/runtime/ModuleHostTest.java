package me.majhrs16.suite.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    private ModuleHost newHost() {
        return new ModuleHost(
                tempDir.resolve("library"),
                tempDir.resolve("config"),
                PlatformInfo.current("test", "1.0"),
                FixtureModules.logger());
    }
}

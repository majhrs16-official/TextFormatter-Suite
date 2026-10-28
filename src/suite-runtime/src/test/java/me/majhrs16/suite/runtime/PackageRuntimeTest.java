package me.majhrs16.suite.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.apt.ManagerFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F3 gate: installing a package activates its module, removing it deactivates,
 * and a failed activation never changes the installation fact.
 */
class PackageRuntimeTest {

    @TempDir
    Path tempDir;

    private final PluginLogger logger = FixtureModules.logger();

    @Test
    void installingActivatesAndRemovingDeactivates() throws Exception {
        try (Fixture fixture = fixture()) {
            Path jar = FixtureModules.buildJar(tempDir, "demo", "1.0.0", "demo",
                    null, null, false, false);
            Path zip = FixtureModules.buildPackageZip(tempDir, "demo", "1.0.0", "stable", jar, null);

            List<ActivationResult> activation = fixture.runtime.installLocal(zip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("demo").orElseThrow());
            assertEquals(1, fixture.manager.packageList().size());
            assertTrue(Files.exists(fixture.manager.getManagerDir()
                    .resolve("library/packages/demo.jar")));
            assertTrue(Files.readString(fixture.manager.getManagerDir().resolve("library/status"))
                    .contains("Status: installed"));
            assertTrue(Files.readString(fixture.manager.getManagerDir().resolve("runtime/modules.status"))
                    .contains("demo ACTIVE"));

            fixture.runtime.removeLocal("demo", "1.0.0", "stable", false);

            assertEquals(ModuleState.INSTALLED, fixture.host.state("demo").orElseThrow());
            assertTrue(fixture.host.activeModules().isEmpty());
            assertTrue(fixture.manager.packageList().isEmpty());
            assertFalse(Files.exists(fixture.manager.getManagerDir()
                    .resolve("library/packages/demo.jar")));
        }
    }

    @Test
    void failedActivationDoesNotChangeTheInstalledState() throws Exception {
        try (Fixture fixture = fixture()) {
            Path jar = FixtureModules.buildJar(tempDir, "broken", "1.0.0", "broken",
                    null, null, true, false);
            Path zip = FixtureModules.buildPackageZip(tempDir, "broken", "1.0.0", "stable", jar, null);

            List<ActivationResult> activation = fixture.runtime.installLocal(zip, false);

            assertEquals(1, activation.size());
            assertFalse(activation.get(0).isSuccess());
            assertEquals(ModuleState.FAILED, fixture.host.state("broken").orElseThrow());
            assertEquals(1, fixture.manager.packageList().size(),
                    "a failed activation must not remove the installed package");
            assertTrue(fixture.host.activeModules().isEmpty());
            assertTrue(Files.readString(fixture.manager.getManagerDir().resolve("library/status"))
                    .contains("Status: installed"));
            assertTrue(Files.readString(fixture.manager.getManagerDir().resolve("runtime/modules.status"))
                    .contains("broken FAILED"));
        }
    }

    @Test
    void activatesConsumerAfterItsProviderWasInstalled() throws Exception {
        try (Fixture fixture = fixture()) {
            Path baseJar = FixtureModules.buildJar(tempDir, "demo-base", "1.0.0", "base",
                    "demo-base", null, false, false);
            Path baseZip = FixtureModules.buildPackageZip(
                    tempDir, "demo-base", "1.0.0", "stable", baseJar, null);
            assertTrue(fixture.runtime.installLocal(baseZip, false).get(0).isSuccess());

            Path consumerJar = FixtureModules.buildJar(tempDir, "demo-consumer", "1.0.0", "consumer",
                    null, "demo-base", false, false);
            Path consumerZip = FixtureModules.buildPackageZip(
                    tempDir, "demo-consumer", "1.0.0", "stable", consumerJar, "demo-base@>=1.0.0");

            List<ActivationResult> activation = fixture.runtime.installLocal(consumerZip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess());
            assertEquals(Set.of("demo-base", "demo-consumer"),
                    Set.copyOf(fixture.host.activeModules()));
            assertEquals(ModuleState.ACTIVE, fixture.host.state("demo-consumer").orElseThrow());
        }
    }

    @Test
    void installsRealTextFormatterPackage() throws Exception {
        String packagePath = System.getProperty("textformatter.package");
        assertNotNull(packagePath, "textformatter.package must be set by the build");
        Path zip = Path.of(packagePath);
        assertTrue(Files.isRegularFile(zip), "missing distribution: " + zip);

        try (Fixture fixture = fixture()) {
            List<ActivationResult> activation = fixture.runtime.installLocal(zip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess(),
                    () -> "activation failed: " + activation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("textformatter").orElseThrow());
            assertTrue(Files.isDirectory(
                    fixture.manager.getManagerDir().resolve("config/textformatter/channels")));
        }
    }

    @Test
    void installsDependencyPackageAndMakesItsJarVisibleToTheConsumer() throws Exception {
        try (Fixture fixture = fixture()) {
            Path dependencyJar = FixtureModules.buildDependencyJar(
                    tempDir, "fixture.dep.Shared", "shared-value");
            Path dependencyZip = FixtureModules.buildDependencyPackageZip(
                    tempDir, "shared-lib", "1.0.0", "stable", dependencyJar);

            assertTrue(fixture.runtime.installLocal(dependencyZip, false).isEmpty(),
                    "dependency packages are installed but not activated as modules");
            assertTrue(fixture.manager.packageList().stream()
                    .anyMatch(entry -> entry.name().equals("shared-lib")));

            Path consumerJar = FixtureModules.buildJarUsingDependency(
                    tempDir, "dep-consumer", "1.0.0", dependencyJar,
                    "fixture.dep.Shared", "value()");
            Path consumerZip = FixtureModules.buildPackageZip(
                    tempDir, "dep-consumer", "1.0.0", "stable", consumerJar,
                    "shared-lib@>=1.0.0");

            List<ActivationResult> activation = fixture.runtime.installLocal(consumerZip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess(),
                    () -> "activation failed: " + activation.get(0).message());
            assertEquals(List.of("shared-value"),
                    fixture.host.services().findAll(CharSequence.class).stream()
                            .map(CharSequence::toString).toList());
        }
    }

    @Test
    void installsRealMessagesPackage() throws Exception {
        String packagePath = System.getProperty("messages.package");
        assertNotNull(packagePath, "messages.package must be set by the build");
        Path zip = Path.of(packagePath);
        assertTrue(Files.isRegularFile(zip), "missing distribution: " + zip);

        try (Fixture fixture = fixture()) {
            List<ActivationResult> activation = fixture.runtime.installLocal(zip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess(),
                    () -> "activation failed: " + activation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("messages").orElseThrow());
        }
    }

    private Fixture fixture() throws IOException {
        Path suiteRoot = tempDir.resolve("suite");
        Path managerDir = suiteRoot.resolve("manager");
        ManagerFacade manager = new ManagerFacade(
                managerDir, logger, "spigot", "x86_64", "linux", 21);
        manager.initialize();

        ModuleHost host = new ModuleHost(
                managerDir.resolve("library"),
                managerDir.resolve("config"),
                PlatformInfo.current("test", "1.0"),
                logger,
                new RuntimeStateStore(managerDir.resolve("runtime"), logger));
        PackageRuntime runtime = new PackageRuntime(manager, host, logger);
        return new Fixture(manager, host, runtime);
    }

    private record Fixture(ManagerFacade manager, ModuleHost host, PackageRuntime runtime)
            implements AutoCloseable {

        @Override
        public void close() {
            host.close();
        }
    }
}

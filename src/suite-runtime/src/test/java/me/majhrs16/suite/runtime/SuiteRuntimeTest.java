package me.majhrs16.suite.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.PlatformPorts;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F4a gate: the neutral composition root starts, activates installed modules,
 * installs/removes packages and exposes a command without importing features.
 */
class SuiteRuntimeTest {

    @TempDir
    Path tempDir;

    private final PluginLogger logger = FixtureModules.logger();
    private final FakePorts ports = new FakePorts();

    @Test
    void startsWithPreinstalledModulesAndStopDeactivatesThem() throws Exception {
        Path jar = FixtureModules.buildJar(tempDir, "pre", "1.0.0", "pre",
                null, null, false, false);
        Path packages = tempDir.resolve("data/manager/library/packages");
        Files.createDirectories(packages);
        Files.copy(jar, packages.resolve("pre.jar"));

        try (SuiteRuntime runtime = new SuiteRuntime(tempDir.resolve("data"), ports, logger)) {
            runtime.start();

            assertTrue(runtime.isStarted());
            assertEquals(List.of("pre"), runtime.activeModules());
            assertEquals(ModuleState.ACTIVE, runtime.snapshot().get("pre"));

            runtime.stop();
            runtime.stop();
            assertFalse(runtime.isStarted());
            assertTrue(Files.exists(tempDir.resolve("data/manager/config/pre/released.marker")),
                    "stopping the runtime must deactivate installed modules");

            runtime.start();
            assertEquals(List.of("pre"), runtime.activeModules(),
                    "a stopped runtime can be started again");
        }

        assertTrue(Files.exists(tempDir.resolve("data/manager/config/pre/released.marker")),
                "closing the runtime must deactivate installed modules");
    }

    @Test
    void installsActivatesAndRemovesThroughTheRuntime() throws Exception {
        try (SuiteRuntime runtime = new SuiteRuntime(tempDir.resolve("data"), ports, logger)) {
            runtime.start();

            Path jar = FixtureModules.buildJar(tempDir, "demo", "1.0.0", "demo",
                    null, null, false, false);
            Path zip = FixtureModules.buildPackageZip(
                    tempDir, "demo", "1.0.0", "stable", jar, null);

            List<ActivationResult> activation = runtime.installLocal(zip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess());
            assertEquals(List.of("demo"), runtime.activeModules());
            assertEquals(1, runtime.manager().packageList().size());

            runtime.removeLocal("demo", "1.0.0", "stable", false);

            assertTrue(runtime.activeModules().isEmpty());
            assertTrue(runtime.manager().packageList().isEmpty());
        }
    }

    @Test
    void commandListsStatesInstallsAndRemoves() throws Exception {
        try (SuiteRuntime runtime = new SuiteRuntime(tempDir.resolve("data"), ports, logger)) {
            runtime.start();
            PlatformPorts.Registration registration = runtime.registerCommands();
            PlatformPorts.Command command = ports.commands.get("suite");
            assertEquals("textformatter.suite.admin", command.permission());

            Path jar = FixtureModules.buildJar(tempDir, "demo", "1.0.0", "demo",
                    null, null, false, false);
            Path zip = FixtureModules.buildPackageZip(
                    tempDir, "demo", "1.0.0", "stable", jar, null);
            Actor console = Actor.unknown("console");

            command.execute(console, List.of("install", zip.toString()));
            assertTrue(ports.sent.stream().anyMatch(line -> line.contains("Activado: demo")));

            command.execute(console, List.of("list"));
            assertTrue(ports.sent.stream().anyMatch(line -> line.contains("demo 1.0.0")));

            command.execute(console, List.of("state"));
            assertTrue(ports.sent.stream().anyMatch(line -> line.contains("demo: ACTIVE")));

            command.execute(console, List.of("remove", "demo"));
            assertTrue(ports.sent.stream().anyMatch(line -> line.contains("Eliminado: demo")));

            registration.close();
            assertFalse(ports.commands.containsKey("suite"));
        }
    }

    @Test
    void operationsBeforeStartFailExplicitly() {
        SuiteRuntime runtime = new SuiteRuntime(tempDir.resolve("data"), ports, logger);

        assertThrows(IllegalStateException.class, runtime::snapshot);
        assertThrows(IllegalStateException.class,
                () -> runtime.installLocal(tempDir.resolve("missing.zip"), false));
    }

    private static final class FakePorts implements PlatformPorts {

        private final List<String> sent = new ArrayList<>();
        private final Map<String, Command> commands = new LinkedHashMap<>();

        @Override
        public PlatformInfo info() {
            return PlatformInfo.current("test", "1.0");
        }

        @Override
        public void deliver(Message message) {
            // The F4a tests exercise the command path, not message delivery.
        }

        @Override
        public void send(Actor recipient, String message) {
            sent.add(message);
        }

        @Override
        public Optional<Actor> findActor(String name) {
            return Optional.empty();
        }

        @Override
        public List<Actor> onlineActors() {
            return List.of();
        }

        @Override
        public Registration registerCommand(Command command) {
            commands.put(command.name(), command);
            return () -> commands.remove(command.name());
        }
    }
}

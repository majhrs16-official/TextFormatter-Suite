package me.majhrs16.suite.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.PlatformPorts;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.port.ChatDelivery;

import net.kyori.adventure.text.Component;

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
            assertNull(command.permission());
            assertEquals(List.of("txf"), command.aliases());

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
    void moduleCommandAndConfigFollowModuleLifecycle() throws Exception {
        try (SuiteRuntime runtime = new SuiteRuntime(tempDir.resolve("data"), ports, logger)) {
            runtime.start();
            runtime.registerCommands();
            PlatformPorts.Command command = ports.commands.get("suite");
            Actor console = Actor.unknown("console");

            Path jar = FixtureModules.buildCommandConfigJar(tempDir, "demo", "1.0.0");
            Path zip = FixtureModules.buildPackageZip(
                    tempDir, "demo", "1.0.0", "stable", jar, null);

            command.execute(console, List.of("install", zip.toString()));
            assertTrue(runtime.configs().find("demo").isPresent());

            ports.sent.clear();
            command.execute(console, List.of("demo", "echo", "hola"));
            assertTrue(ports.sent.stream().anyMatch(line -> line.equals("echo:hola")));

            runtime.removeLocal("demo", "1.0.0", "stable", false);
            assertTrue(runtime.configs().find("demo").isEmpty());

            ports.sent.clear();
            command.execute(console, List.of("demo", "echo", "hola"));
            assertTrue(ports.sent.stream().anyMatch(
                    line -> line.contains("Subcomando desconocido")));
        }
    }

    @Test
    void rootCommandCanBeClosedAndRegisteredAgain() throws Exception {
        try (SuiteRuntime runtime = new SuiteRuntime(tempDir.resolve("data"), ports, logger)) {
            runtime.start();
            PlatformPorts.Registration first = runtime.registerCommands();
            assertTrue(ports.commands.containsKey("suite"));

            first.close();
            assertFalse(ports.commands.containsKey("suite"));

            PlatformPorts.Registration second = runtime.registerCommands();
            assertTrue(ports.commands.containsKey("suite"));
            second.close();
            assertFalse(ports.commands.containsKey("suite"));
        }
    }

    @Test
    void dispatchWithoutActivePackagesFailsExplicitly() throws Exception {
        try (SuiteRuntime runtime = new SuiteRuntime(tempDir.resolve("data"), ports, logger)) {
            runtime.start();
            MessageDispatch dispatch = runtime.services()
                .find(MessageDispatch.class)
                .orElseThrow();
            Message message = Message.builder()
                .type(MessageType.CHAT)
                .text("hello")
                .channel("chat")
                .build();

            assertFalse(runtime.pipelineAvailable());
            assertTrue(runtime.pipelineError().contains("missing service"));
            assertThrows(IllegalStateException.class, () -> dispatch.dispatch(message));
        }
    }

    @Test
    void buildsMessagePipelineFromActivePackagesAndDispatches() throws Exception {
        FakeDelivery delivery = new FakeDelivery();
        try (SuiteRuntime runtime = new SuiteRuntime(tempDir.resolve("data"), ports, logger)) {
            runtime.registerPlatformService(ChatDelivery.class, delivery);
            runtime.registerPlatformService(ActorDirectory.class, new FakeActors());
            runtime.registerPlatformService(PermissionChecker.class, PermissionChecker.ALLOW_ALL);
            runtime.start();

            assertTrue(runtime.installLocal(packageFromProperty("logger.package"), false).isEmpty());
            assertTrue(runtime.installLocal(packageFromProperty("textformatter.package"), false)
                    .get(0).isSuccess());
            assertTrue(runtime.installLocal(packageFromProperty("chattranslator.package"), false)
                    .get(0).isSuccess());
            assertTrue(runtime.installLocal(packageFromProperty("iflow-vm.package"), false)
                    .get(0).isSuccess());

            assertTrue(runtime.pipelineAvailable(), runtime::pipelineError);
            Actor sender = Actor.unknown("Steve").withLanguage(Language.EN);
            Message message = Message.builder()
                .type(MessageType.CHAT)
                .sender(sender)
                .direction(Direction.initiator())
                .text("hello")
                .channel("chat")
                .build();

            runtime.messageDispatch().dispatch(message);

            assertEquals(1, delivery.delivered.size());
            assertEquals("Steve", delivery.delivered.get(0).message().sender().name());
        }
    }

    private static Path packageFromProperty(String name) {
        String path = System.getProperty(name);
        assertTrue(path != null && Files.isRegularFile(Path.of(path)),
                name + " must point to a built distribution");
        return Path.of(path);
    }

    @Test
    void exposesAdapterPlatformServicesBeforeModulesActivate() throws Exception {
        try (SuiteRuntime runtime = new SuiteRuntime(tempDir.resolve("data"), ports, logger)) {
            runtime.registerPlatformService(PluginLogger.class, logger);
            runtime.start();

            assertTrue(runtime.services().find(PluginLogger.class).isPresent());
            assertThrows(IllegalStateException.class,
                () -> runtime.registerPlatformService(String.class, "late"));
        }
    }

    @Test
    void operationsBeforeStartFailExplicitly() {
        SuiteRuntime runtime = new SuiteRuntime(tempDir.resolve("data"), ports, logger);

        assertThrows(IllegalStateException.class, runtime::snapshot);
        assertThrows(IllegalStateException.class,
                () -> runtime.installLocal(tempDir.resolve("missing.zip"), false));
    }

    private record Delivered(Actor recipient, Component rendered, Message message) {}

    private static final class FakeDelivery implements ChatDelivery {

        private final List<Delivered> delivered = new ArrayList<>();

        @Override
        public void deliver(Actor recipient, Component rendered, Message original) {
            delivered.add(new Delivered(recipient, rendered, original));
        }

        @Override
        public void deliverConsole(Component rendered) {
            // Not used by the initiator-only assertion.
        }

        @Override
        public void playSound(Actor recipient, SoundSpec sound) {
            // No-op: sounds are not part of this pipeline assertion.
        }

        @Override
        public boolean hasSound(String soundName) {
            return true;
        }
    }

    private static final class FakeActors implements ActorDirectory {

        private static final Actor CONSOLE = Actor.console("Console", Language.EN);

        @Override
        public List<Actor> onlinePlayers() {
            return List.of();
        }

        @Override
        public Optional<Actor> byUuid(java.util.UUID uuid) {
            return Optional.empty();
        }

        @Override
        public Optional<Actor> byName(String name) {
            return Optional.empty();
        }

        @Override
        public Actor console() {
            return CONSOLE;
        }
    }

    private static final class FakePorts implements PlatformPorts {

        private final List<String> sent = new ArrayList<>();
        private final List<Message> delivered = new ArrayList<>();
        private final Map<String, Command> commands = new LinkedHashMap<>();

        @Override
        public PlatformInfo info() {
            return PlatformInfo.current("test", "1.0");
        }

        @Override
        public void deliver(Message message) {
            delivered.add(message);
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
        public boolean hasPermission(Actor actor, String permission) {
            return true;
        }

        @Override
        public Registration registerCommand(Command command) {
            commands.put(command.name(), command);
            return () -> commands.remove(command.name());
        }
    }
}

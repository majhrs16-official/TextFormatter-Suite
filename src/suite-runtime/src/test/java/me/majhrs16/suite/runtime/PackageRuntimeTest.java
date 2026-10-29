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

import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslatorProvider;
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

    @Test
    void installsRealPresetsPackage() throws Exception {
        Path zip = packageFromProperty("presets.package");

        try (Fixture fixture = fixture()) {
            List<ActivationResult> activation = fixture.runtime.installLocal(zip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess(),
                    () -> "activation failed: " + activation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("presets").orElseThrow());
            assertTrue(Files.isDirectory(
                    fixture.manager.getManagerDir().resolve("config/presets")),
                    "the activator must create the custom preset directory");
        }
    }

    @Test
    void installsRealChatTranslatorPackage() throws Exception {
        String packagePath = System.getProperty("chattranslator.package");
        assertNotNull(packagePath, "chattranslator.package must be set by the build");
        Path zip = Path.of(packagePath);
        assertTrue(Files.isRegularFile(zip), "missing distribution: " + zip);

        try (Fixture fixture = fixture()) {
            List<ActivationResult> activation = fixture.runtime.installLocal(zip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess(),
                    () -> "activation failed: " + activation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("chattranslator").orElseThrow());
        }
    }

    @Test
    void installsRealIflowVmPackageWithItsLoggerDependency() throws Exception {
        Path loggerZip = packageFromProperty("logger.package");
        Path textformatterZip = packageFromProperty("textformatter.package");
        Path vmZip = packageFromProperty("iflow-vm.package");

        try (Fixture fixture = fixture()) {
            assertTrue(fixture.runtime.installLocal(loggerZip, false).isEmpty(),
                    "dependency packages are installed but not activated");
            assertTrue(fixture.runtime.installLocal(textformatterZip, false).get(0).isSuccess());

            List<ActivationResult> activation = fixture.runtime.installLocal(vmZip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess(),
                    () -> "activation failed: " + activation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("iflow-vm").orElseThrow());
            assertTrue(fixture.host.services().find(Router.class).isPresent(),
                    "iflow-vm must publish the shared Router service");
        }
    }

    @Test
    void installsRealIflowFirewallPackageAfterChannelApi() throws Exception {
        Path textformatterZip = packageFromProperty("textformatter.package");
        Path firewallZip = packageFromProperty("iflow-firewall.package");

        try (Fixture fixture = fixture()) {
            List<ActivationResult> formatterActivation =
                    fixture.runtime.installLocal(textformatterZip, false);
            assertTrue(formatterActivation.get(0).isSuccess(),
                    () -> "textformatter failed: " + formatterActivation.get(0).message());

            List<ActivationResult> activation = fixture.runtime.installLocal(firewallZip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess(),
                    () -> "activation failed: " + activation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("iflow-firewall").orElseThrow());
            assertTrue(fixture.host.services().find(Router.class).isPresent(),
                    "iflow-firewall must publish the shared Router service");
        }
    }

    @Test
    void installsRealIflowWasmPackage() throws Exception {
        Path wasmZip = packageFromProperty("iflow-wasm.package");

        try (Fixture fixture = fixture()) {
            List<ActivationResult> activation = fixture.runtime.installLocal(wasmZip, false);

            assertEquals(1, activation.size());
            assertTrue(activation.get(0).isSuccess(),
                    () -> "activation failed: " + activation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("iflow-wasm").orElseThrow());
        }
    }

    @Test
    void installsTranslationProvidersAndRefreshesChatTranslator() throws Exception {
        Path transportZip = packageFromProperty("transport.package");
        Path chatTranslatorZip = packageFromProperty("chattranslator.package");
        Path gtranslateZip = packageFromProperty("gtranslate.package");
        Path ltranslateZip = packageFromProperty("ltranslate.package");

        try (Fixture fixture = fixture()) {
            Path translators = fixture.manager.getManagerDir()
                    .resolve("config/chattranslator/translators");
            Files.createDirectories(translators);
            Files.writeString(translators.resolve("google.yml"), """
                provider: google
                active: true
                """);
            Files.writeString(translators.resolve("libre.yml"), """
                provider: libre
                active: true
                base-url: https://libre.example
                """);

            assertTrue(fixture.runtime.installLocal(transportZip, false).isEmpty(),
                    "transport is a dependency package");
            assertTrue(fixture.runtime.installLocal(chatTranslatorZip, false).get(0).isSuccess());
            assertTrue(fixture.runtime.installLocal(gtranslateZip, false).get(0).isSuccess());
            assertTrue(fixture.runtime.installLocal(ltranslateZip, false).get(0).isSuccess());

            assertEquals(ModuleState.ACTIVE, fixture.host.state("gtranslate").orElseThrow());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("ltranslate").orElseThrow());

            List<String> providerNames = fixture.host.services()
                    .findAll(TranslatorProvider.class).stream()
                    .map(TranslatorProvider::name)
                    .toList();
            assertEquals(Set.of("google", "libre"), Set.copyOf(providerNames));

            TranslatorManager manager = fixture.host.services()
                    .find(TranslatorManager.class).orElseThrow();
            List<String> configuredNames = manager.providers().stream()
                    .map(Translator::name)
                    .toList();
            assertEquals(Set.of("google", "libre"), Set.copyOf(configuredNames));

            assertTrue(fixture.host.deactivate("gtranslate").isClean());
            List<String> afterUnload = manager.providers().stream()
                    .map(Translator::name)
                    .toList();
            assertEquals(Set.of("libre"), Set.copyOf(afterUnload),
                    "chattranslator must drop the provider of an unloaded module");
        }
    }

    @Test
    void installsSyncBusPackageAndHotRegistersSinkModule() throws Exception {
        Path busZip = packageFromProperty("sync-bus.package");

        try (Fixture fixture = fixture()) {
            List<ActivationResult> busActivation = fixture.runtime.installLocal(busZip, false);
            assertTrue(busActivation.get(0).isSuccess(),
                    () -> "sync-bus activation failed: " + busActivation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("sync-bus").orElseThrow());

            SyncBus bus = fixture.host.services().find(SyncBus.class).orElseThrow();
            assertTrue(bus.sinkNames().isEmpty());

            Path sinkJar = FixtureModules.buildSinkJar(
                    tempDir, "sink-fixture", "1.0.0", "fixture-sink");
            Path sinkZip = FixtureModules.buildPackageZip(
                    tempDir, "sink-fixture", "1.0.0", "stable", sinkJar, "sync-bus@>=2.1.0");

            List<ActivationResult> sinkActivation = fixture.runtime.installLocal(sinkZip, false);
            assertTrue(sinkActivation.get(0).isSuccess(),
                    () -> "sink activation failed: " + sinkActivation.get(0).message());
            assertEquals(Set.of("fixture-sink"), bus.sinkNames(),
                    "a sink registered after the bus started must be live immediately");

            fixture.runtime.removeLocal("sink-fixture", "1.0.0", "stable", false);
            assertTrue(bus.sinkNames().isEmpty(),
                    "unloading the sink package must unregister it from the running bus");
            assertTrue(bus.isEmpty());
        }
    }

    @Test
    void installsNeutralDiscordPackageWithATransportAdapter() throws Exception {
        Path commonZip = packageFromProperty("sync-common.package");
        Path busZip = packageFromProperty("sync-bus.package");
        Path discordZip = packageFromProperty("sync-discord.package");

        try (Fixture fixture = fixture()) {
            assertTrue(fixture.runtime.installLocal(commonZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(busZip, false).get(0).isSuccess());

            Path configDir = fixture.manager.getManagerDir().resolve("config/sync-discord");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("config.yml"), """
                    enabled: true
                    token: test-token
                    channel: 42
                    transport: fake
                    """);

            // The neutral package activates before its transport adapter exists.
            List<ActivationResult> activation = fixture.runtime.installLocal(discordZip, false);
            assertTrue(activation.get(0).isSuccess(),
                    () -> "sync-discord activation failed: " + activation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("sync-discord").orElseThrow());

            SyncBus bus = fixture.host.services().find(SyncBus.class).orElseThrow();
            assertTrue(bus.sinkNames().isEmpty(),
                    "without a transport adapter there is no sink to register");

            Path adapterJar = FixtureModules.buildDiscordTransportJar(
                    tempDir, "discord-fake", "1.0.0");
            Path adapterZip = FixtureModules.buildPackageZip(
                    tempDir, "discord-fake", "1.0.0", "stable", adapterJar, null);
            List<ActivationResult> adapterActivation =
                    fixture.runtime.installLocal(adapterZip, false);
            assertTrue(adapterActivation.get(0).isSuccess(),
                    () -> "discord adapter activation failed: "
                            + adapterActivation.get(0).message());
            assertEquals(Set.of("discord"), bus.sinkNames(),
                    "the sink must attach when the transport adapter appears");

            fixture.runtime.removeLocal("discord-fake", "1.0.0", "stable", false);
            assertTrue(bus.sinkNames().isEmpty(),
                    "unloading the transport adapter must remove the sink");

            fixture.runtime.removeLocal("sync-discord", "2.1.0", "stable", false);
            assertTrue(bus.sinkNames().isEmpty(),
                    "unloading sync-discord must unregister its sink");
        }
    }

    @Test
    void installsNeutralVelocityPackageWithATransportAdapter() throws Exception {
        Path transportZip = packageFromProperty("transport.package");
        Path commonZip = packageFromProperty("sync-common.package");
        Path busZip = packageFromProperty("sync-bus.package");
        Path velocityZip = packageFromProperty("sync-velocity-core.package");

        try (Fixture fixture = fixture()) {
            assertTrue(fixture.runtime.installLocal(transportZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(commonZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(busZip, false).get(0).isSuccess());

            Path configDir = fixture.manager.getManagerDir().resolve("config/sync-velocity-core");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("config.yml"), """
                    enabled: true
                    transport: fake
                    servers: [server1]
                    mapping: "* -> server1"
                    """);

            List<ActivationResult> activation = fixture.runtime.installLocal(velocityZip, false);
            assertTrue(activation.get(0).isSuccess(),
                    () -> "sync-velocity-core activation failed: " + activation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("sync-velocity-core").orElseThrow());

            SyncBus bus = fixture.host.services().find(SyncBus.class).orElseThrow();
            assertTrue(bus.sinkNames().isEmpty(),
                    "without a Velocity adapter there is no sink to register");

            Path adapterJar = FixtureModules.buildVelocityTransportJar(
                    tempDir, "velocity-fake", "1.0.0");
            Path adapterZip = FixtureModules.buildPackageZip(
                    tempDir, "velocity-fake", "1.0.0", "stable", adapterJar, null);
            List<ActivationResult> adapterActivation =
                    fixture.runtime.installLocal(adapterZip, false);
            assertTrue(adapterActivation.get(0).isSuccess(),
                    () -> "velocity adapter activation failed: "
                            + adapterActivation.get(0).message());
            assertEquals(Set.of("velocity"), bus.sinkNames(),
                    "the sink must attach when the Velocity adapter appears");

            fixture.runtime.removeLocal("velocity-fake", "1.0.0", "stable", false);
            assertTrue(bus.sinkNames().isEmpty(),
                    "unloading the Velocity adapter must remove the sink");

            fixture.runtime.removeLocal("sync-velocity-core", "2.1.0", "stable", false);
            assertTrue(bus.sinkNames().isEmpty(),
                    "unloading sync-velocity-core must unregister its sink");
        }
    }

    @Test
    void installsSyncSocketPackageAndRegistersConfiguredSinks() throws Exception {
        Path transportZip = packageFromProperty("transport.package");
        Path commonZip = packageFromProperty("sync-common.package");
        Path busZip = packageFromProperty("sync-bus.package");
        Path socketZip = packageFromProperty("sync-socket.package");

        try (Fixture fixture = fixture()) {
            assertTrue(fixture.runtime.installLocal(transportZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(commonZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(busZip, false).get(0).isSuccess());

            Path configDir = fixture.manager.getManagerDir().resolve("config/sync-socket");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("tcp.yml"), """
                    enabled: true
                    remoteHost: 127.0.0.1
                    remotePort: 9
                    localPort: 0
                    """);
            Files.writeString(configDir.resolve("udp.yml"), """
                    enabled: true
                    remoteHost: 127.0.0.1
                    remotePort: 9
                    localPort: 0
                    """);

            List<ActivationResult> activation = fixture.runtime.installLocal(socketZip, false);
            assertTrue(activation.get(0).isSuccess(),
                    () -> "sync-socket activation failed: " + activation.get(0).message());

            SyncBus bus = fixture.host.services().find(SyncBus.class).orElseThrow();
            assertEquals(Set.of("tcp", "udp"), bus.sinkNames());

            fixture.runtime.removeLocal("sync-socket", "2.1.0", "stable", false);
            assertTrue(bus.sinkNames().isEmpty(),
                    "unloading sync-socket must unregister both sinks");
        }
    }

    @Test
    void installsSyncTelegramPackageAndRegistersConfiguredSink() throws Exception {
        Path transportZip = packageFromProperty("transport.package");
        Path commonZip = packageFromProperty("sync-common.package");
        Path busZip = packageFromProperty("sync-bus.package");
        Path telegramZip = packageFromProperty("sync-telegram.package");

        try (Fixture fixture = fixture()) {
            assertTrue(fixture.runtime.installLocal(transportZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(commonZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(busZip, false).get(0).isSuccess());

            Path configDir = fixture.manager.getManagerDir().resolve("config/sync-telegram");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("config.yml"), """
                    enabled: true
                    token: test-token
                    chatId: 123456
                    """);

            List<ActivationResult> activation = fixture.runtime.installLocal(telegramZip, false);
            assertTrue(activation.get(0).isSuccess(),
                    () -> "sync-telegram activation failed: " + activation.get(0).message());

            SyncBus bus = fixture.host.services().find(SyncBus.class).orElseThrow();
            assertEquals(Set.of("telegram"), bus.sinkNames());

            fixture.runtime.removeLocal("sync-telegram", "2.1.0", "stable", false);
            assertTrue(bus.sinkNames().isEmpty(),
                    "unloading sync-telegram must unregister its sink");
        }
    }

    @Test
    void installsSyncHttpPackageAndRegistersConfiguredSink() throws Exception {
        Path transportZip = packageFromProperty("transport.package");
        Path commonZip = packageFromProperty("sync-common.package");
        Path busZip = packageFromProperty("sync-bus.package");
        Path httpZip = packageFromProperty("sync-http.package");

        try (Fixture fixture = fixture()) {
            assertTrue(fixture.runtime.installLocal(transportZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(commonZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(busZip, false).get(0).isSuccess());

            Path configDir = fixture.manager.getManagerDir().resolve("config/sync-http");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("config.yml"), """
                    enabled: true
                    outboundUrl: "http://127.0.0.1:9/hook"
                    inboundPort: 0
                    inboundPath: "/hook"
                    bind: "127.0.0.1"
                    """);

            List<ActivationResult> activation = fixture.runtime.installLocal(httpZip, false);
            assertTrue(activation.get(0).isSuccess(),
                    () -> "sync-http activation failed: " + activation.get(0).message());

            SyncBus bus = fixture.host.services().find(SyncBus.class).orElseThrow();
            assertEquals(Set.of("http"), bus.sinkNames());

            fixture.runtime.removeLocal("sync-http", "2.1.0", "stable", false);
            assertTrue(bus.sinkNames().isEmpty(),
                    "unloading sync-http must unregister its sink");
        }
    }

    @Test
    void installsSyncWebSocketPackageAndRegistersConfiguredSink() throws Exception {
        Path transportZip = packageFromProperty("transport.package");
        Path commonZip = packageFromProperty("sync-common.package");
        Path busZip = packageFromProperty("sync-bus.package");
        Path webSocketZip = packageFromProperty("sync-websocket.package");
        int port = freePort();

        try (Fixture fixture = fixture()) {
            assertTrue(fixture.runtime.installLocal(transportZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(commonZip, false).isEmpty());
            assertTrue(fixture.runtime.installLocal(busZip, false).get(0).isSuccess());

            Path configDir = fixture.manager.getManagerDir().resolve("config/sync-websocket");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("config.yml"), """
                    enabled: true
                    token: test-token
                    port: %d
                    bind: 127.0.0.1
                    """.formatted(port));

            List<ActivationResult> activation = fixture.runtime.installLocal(webSocketZip, false);
            assertTrue(activation.get(0).isSuccess(),
                    () -> "sync-websocket activation failed: " + activation.get(0).message());

            SyncBus bus = fixture.host.services().find(SyncBus.class).orElseThrow();
            assertEquals(Set.of("websocket"), bus.sinkNames());

            fixture.runtime.removeLocal("sync-websocket", "2.1.0", "stable", false);
            assertTrue(bus.sinkNames().isEmpty(),
                    "unloading sync-websocket must unregister its sink");
        }
    }

    @Test
    void installsObservabilityPackageAndServesHealthEndpoint() throws Exception {
        Path commonZip = packageFromProperty("sync-common.package");
        Path observabilityZip = packageFromProperty("observability.package");
        int port = freePort();

        try (Fixture fixture = fixture()) {
            assertTrue(fixture.runtime.installLocal(commonZip, false).isEmpty());

            Path configDir = fixture.manager.getManagerDir().resolve("config/observability");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("config.yml"), """
                    enabled: true
                    metricsPort: %d
                    metricsBind: 127.0.0.1
                    """.formatted(port));

            List<ActivationResult> activation = fixture.runtime.installLocal(observabilityZip, false);
            assertTrue(activation.get(0).isSuccess(),
                    () -> "observability activation failed: " + activation.get(0).message());
            assertEquals(ModuleState.ACTIVE, fixture.host.state("observability").orElseThrow());

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpResponse<String> response = client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/health"))
                            .GET()
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\": \"UP\""));

            fixture.runtime.removeLocal("observability", "2.1.0", "stable", false);
            assertEquals(ModuleState.INSTALLED,
                    fixture.host.state("observability").orElseThrow(),
                    "unloading observability must deactivate the package");
            assertFalse(fixture.host.activeModules().contains("observability"));
        }
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path packageFromProperty(String name) {
        String packagePath = System.getProperty(name);
        assertNotNull(packagePath, name + " must be set by the build");
        Path zip = Path.of(packagePath);
        assertTrue(Files.isRegularFile(zip), "missing distribution: " + zip);
        return zip;
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

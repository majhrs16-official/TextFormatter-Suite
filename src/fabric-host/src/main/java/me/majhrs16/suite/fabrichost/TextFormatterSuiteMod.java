package me.majhrs16.suite.fabrichost;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.inworld.InWorldService;
import me.majhrs16.suite.api.render.ChatDelivery;
import me.majhrs16.suite.api.runtime.PlatformHandle;
import me.majhrs16.suite.api.runtime.PlatformPorts;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageRenderer;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.runtime.DefaultConfigInstaller;
import me.majhrs16.suite.runtime.SuiteRuntime;
import me.majhrs16.suite.runtime.YamlUserLanguageStore;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin Fabric bootstrap of the suite runtime.
 *
 * <p>Creates the platform ports, registers the adapter-owned services and
 * starts {@link SuiteRuntime}. Feature wiring and event capture live in
 * packages ({@code sync-fabric} owns the latter), not here.</p>
 */
public final class TextFormatterSuiteMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("TextFormatterSuite");
    private static final AtomicReference<CommandDispatcher<ServerCommandSource>> PENDING_DISPATCHER =
            new AtomicReference<>();

    private static MinecraftServer server;
    private static SuiteRuntime runtime;
    private static FabricPlatformPorts ports;
    private static FabricInWorldHandler inworldHandler;
    private static PlatformPorts.Registration commandRegistration;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            FabricPlatformPorts current = ports;
            if (current == null) {
                PENDING_DISPATCHER.set(dispatcher);
            } else {
                current.bindDispatcher(dispatcher);
            }
        });
    }

    private void onServerStarting(MinecraftServer startedServer) {
        server = startedServer;
        Path folder = startedServer.getRunDirectory().resolve("textformatter-suite");
        PluginLogger logger = logger();
        DefaultConfigInstaller.install(folder, TextFormatterSuiteMod.class::getResourceAsStream, logger);

        UserLanguageStore languages = new YamlUserLanguageStore(folder);
        FabricActorDirectory directory = new FabricActorDirectory(startedServer, languages);
        AtomicReference<SuiteRuntime> runtimeRef = new AtomicReference<>();
        FabricPlatformPorts createdPorts = new FabricPlatformPorts(
                startedServer,
                directory,
                message -> {
                    SuiteRuntime current = runtimeRef.get();
                    if (current == null) {
                        throw new IllegalStateException("SuiteRuntime is not available yet");
                    }
                    current.messageDispatch().dispatch(message);
                },
                startedServer.getVersion());

        SuiteRuntime created = new SuiteRuntime(folder, createdPorts, logger);
        runtimeRef.set(created);
        created.registerPlatformService(ChatDelivery.class, new FabricChatDelivery(startedServer));
        created.registerPlatformService(ActorDirectory.class, directory);
        created.registerPlatformService(PermissionChecker.class, createdPorts::hasPermission);
        created.registerPlatformService(UserLanguageStore.class, languages);
        created.registerPlatformService(PlaceholderResolver.class, new FabricPlaceholderResolver());
        created.registerPlatformService(PluginLogger.class, logger);
        created.registerPlatformService(PlatformHandle.class, (PlatformHandle) () -> startedServer);

        try {
            created.start();
        } catch (IOException failure) {
            LOGGER.error("Cannot start the suite runtime", failure);
            return;
        }

        ports = createdPorts;
        runtime = created;
        CommandDispatcher<ServerCommandSource> pending = PENDING_DISPATCHER.getAndSet(null);
        if (pending != null) {
            createdPorts.bindDispatcher(pending);
        }
        commandRegistration = created.registerCommands();
        registerInWorld(created, logger, languages);
        LOGGER.info("TextFormatter Suite runtime started");
    }

    private void onServerStopping(MinecraftServer stoppingServer) {
        if (commandRegistration != null) {
            commandRegistration.close();
            commandRegistration = null;
        }
        if (inworldHandler != null) {
            inworldHandler.unregister();
            inworldHandler = null;
        }
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        ports = null;
        server = null;
        PENDING_DISPATCHER.set(null);
    }

    /** @return the neutral runtime, or {@code null} while the server is off. */
    public static SuiteRuntime runtime() {
        return runtime;
    }

    private static void registerInWorld(SuiteRuntime created, PluginLogger logger,
                                        UserLanguageStore languages) {
        InWorldService handler = created.services().find(InWorldService.class).orElse(null);
        MessageRenderer renderer = created.services().find(MessageRenderer.class).orElse(null);
        if (handler == null || renderer == null || server == null) {
            logger.debug("In-world package is not active; callbacks not registered");
            return;
        }
        inworldHandler = new FabricInWorldHandler(
                handler, renderer, created.messageDispatch(), server, logger,
                languages, created.defaultLanguage());
        inworldHandler.registerListeners();
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) { LOGGER.info(format(m, a)); }
            @Override public void warn(String m, Object... a) { LOGGER.warn(format(m, a)); }
            @Override public void error(String m, Object... a) { LOGGER.error(format(m, a)); }
            @Override public void error(String m, Throwable t) { LOGGER.error(format(m), t); }
            @Override public void debug(String m, Object... a) {
                if (Boolean.getBoolean("textformattersuite.debug")) {
                    LOGGER.info("[debug] " + format(m, a));
                }
            }
        };
    }

    private static String format(String message, Object... args) {
        return args == null || args.length == 0 ? message : String.format(message, args);
    }
}

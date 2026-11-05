package me.majhrs16.suite.syncfabric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformHandle;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.synccommon.SyncEventProcessor;

import net.minecraft.server.MinecraftServer;

/**
 * Runtime activator of the {@code sync-fabric} package.
 *
 * <p>Registers the global Fabric callbacks in {@code start()}. Fabric API has
 * no unregister operation, so a hot deactivation marks the module
 * {@code RESTART_REQUIRED}; a full server shutdown only makes the callbacks
 * inert.</p>
 */
public final class SyncFabricActivator implements ModuleActivator {

    private ModuleContext context;
    private FabricEventListener listener;
    private boolean registered;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-fabric")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(1, 0, 0))
                .jvmRange(21, 0)
                .provide(Capability.of("sync-fabric", SemVer.of(1, 0, 0)))
                .require(Requirement.atLeast("channel-api", SemVer.of(1, 0, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) throws Exception {
        this.context = context;
        copyDefaultConfig(context.configDir());
        SyncFabricConfig config = SyncFabricConfig.loadFromConfigDir(context.configDir());
        if (!config.enabled()) {
            context.logger().info("sync-fabric is disabled by configuration");
            return;
        }

        PlatformHandle handle = context.services().find(PlatformHandle.class)
                .orElseThrow(() -> new IllegalStateException(
                        "sync-fabric requires the platform handle"));
        handle.unwrap(MinecraftServer.class);

        SyncEventProcessor processor = new SyncEventProcessor(
                required(context, MessageDispatch.class),
                required(context, ChannelRegistry.class),
                required(context, UserLanguageStore.class),
                required(context, PermissionChecker.class),
                context.logger());

        FabricEventListener created = new FabricEventListener(
                processor,
                required(context, ActorDirectory.class),
                config,
                context.scheduler(),
                context.logger());
        listener = created;
        created.register();
        registered = true;
        context.resources().onRelease(created::deactivate);
    }

    @Override
    public void stop() {
        FabricEventListener current = listener;
        listener = null;
        if (current != null) {
            current.deactivate();
        }
        if (registered) {
            registered = false;
            if (context != null && !context.isShuttingDown()) {
                throw new IllegalStateException(
                        "Fabric event callbacks cannot be unregistered; restart required");
            }
        }
    }

    private static <T> T required(ModuleContext context, Class<T> type) {
        return context.services().find(type)
                .orElseThrow(() -> new IllegalStateException(
                        "sync-fabric requires " + type.getSimpleName()));
    }

    private static void copyDefaultConfig(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path target = configDir.resolve("config.yml");
        if (Files.exists(target)) {
            return;
        }
        try (InputStream input = SyncFabricActivator.class.getResourceAsStream("/defaults/config.yml")) {
            if (input == null) {
                return;
            }
            try {
                Files.copy(input, target);
            } catch (FileAlreadyExistsException ignored) {
                // Another activation won the race; keep the existing file.
            }
        }
    }
}

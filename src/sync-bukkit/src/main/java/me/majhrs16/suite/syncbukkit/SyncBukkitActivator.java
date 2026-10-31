package me.majhrs16.suite.syncbukkit;

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

import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

/**
 * Runtime activator of the {@code sync-bukkit} package.
 *
 * <p>The package owns the Bukkit chat/join/quit/death listeners. It resolves
 * the native {@link Plugin} through {@link PlatformHandle}, registers the
 * listener in {@code start()} and unregisters it in {@code stop()}. The host
 * JAR contains no event handler.</p>
 */
public final class SyncBukkitActivator implements ModuleActivator {

    private BukkitChatListener listener;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-bukkit")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(21, 0)
                .provide(Capability.of("sync-bukkit", SemVer.of(2, 1, 0)))
                .require(Requirement.atLeast("channel-api", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) throws Exception {
        copyDefaultConfig(context.configDir());
        SyncBukkitConfig config = SyncBukkitConfig.loadFromConfigDir(context.configDir());
        if (!config.enabled()) {
            context.logger().info("sync-bukkit is disabled by configuration");
            return;
        }

        PlatformHandle handle = context.services().find(PlatformHandle.class)
                .orElseThrow(() -> new IllegalStateException(
                        "sync-bukkit requires the platform handle"));
        Plugin plugin = handle.unwrap(Plugin.class);

        SyncEventProcessor processor = new SyncEventProcessor(
                required(context, MessageDispatch.class),
                required(context, ChannelRegistry.class),
                required(context, UserLanguageStore.class),
                required(context, PermissionChecker.class),
                context.logger());

        BukkitChatListener created = new BukkitChatListener(
                processor,
                required(context, ActorDirectory.class),
                config,
                context.scheduler(),
                context.logger());
        plugin.getServer().getPluginManager().registerEvents(created, plugin);
        listener = created;
        context.resources().onRelease(() -> {
            created.deactivate();
            HandlerList.unregisterAll(created);
        });
        context.logger().info("sync-bukkit listeners registered");
    }

    @Override
    public void stop() {
        BukkitChatListener current = listener;
        listener = null;
        if (current != null) {
            current.deactivate();
            HandlerList.unregisterAll(current);
        }
    }

    private static <T> T required(ModuleContext context, Class<T> type) {
        return context.services().find(type)
                .orElseThrow(() -> new IllegalStateException(
                        "sync-bukkit requires " + type.getSimpleName()));
    }

    private static void copyDefaultConfig(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path target = configDir.resolve("config.yml");
        if (Files.exists(target)) {
            return;
        }
        try (InputStream input = SyncBukkitActivator.class.getResourceAsStream("/defaults/config.yml")) {
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

package me.majhrs16.suite.spigothost;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.inworld.InWorldService;
import me.majhrs16.suite.api.render.ChatDelivery;
import me.majhrs16.suite.api.runtime.PlatformPorts;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageRenderer;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.runtime.SuiteRuntime;
import me.majhrs16.suite.runtime.YamlUserLanguageStore;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Thin Spigot bootstrap of the suite runtime.
 *
 * <p>This class only creates the platform ports, registers the adapter-owned
 * services and starts {@link SuiteRuntime}. It contains no feature wiring:
 * formatters, translators, routers, sinks and commands live in packages and
 * are resolved by the runtime.</p>
 *
 * <p>Event capture (chat/join/quit/death) belongs to {@code sync-bukkit}; it is
 * intentionally absent here.</p>
 */
public final class TextFormatterSuitePlugin extends JavaPlugin {

    private BukkitAudiences audiences;
    private SuiteRuntime runtime;
    private PlatformPorts.Registration commandRegistration;
    private SpigotInWorldListener inworldListener;

    @Override
    public void onEnable() {
        audiences = BukkitAudiences.create(this);
        PluginLogger logger = logger();
        Path folder = getDataFolder().toPath();
        copyDefaultsIfMissing(folder);

        AtomicReference<SuiteRuntime> runtimeRef = new AtomicReference<>();
        UserLanguageStore languages = new YamlUserLanguageStore(folder);
        SpigotActorDirectory directory = new SpigotActorDirectory(languages);

        SpigotPlatformPorts ports = new SpigotPlatformPorts(
                this,
                audiences,
                directory,
                message -> {
                    SuiteRuntime current = runtimeRef.get();
                    if (current == null) {
                        throw new IllegalStateException("SuiteRuntime is not available yet");
                    }
                    current.messageDispatch().dispatch(message);
                },
                commandMap(),
                getServer().getVersion(),
                getName().toLowerCase(java.util.Locale.ROOT));

        SuiteRuntime created = new SuiteRuntime(folder, ports, logger);
        runtimeRef.set(created);
        created.registerPlatformService(ChatDelivery.class, new SpigotChatDelivery(this, audiences));
        created.registerPlatformService(ActorDirectory.class, directory);
        created.registerPlatformService(PermissionChecker.class, ports::hasPermission);
        created.registerPlatformService(UserLanguageStore.class, languages);
        created.registerPlatformService(PlaceholderResolver.class, new SpigotPlaceholderResolver());
        created.registerPlatformService(PluginLogger.class, logger);

        try {
            created.start();
        } catch (IOException failure) {
            getLogger().severe("Cannot start the suite runtime: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        runtime = created;
        commandRegistration = created.registerCommands();
        registerInWorld(created, logger, languages);
        getLogger().info("TextFormatter Suite runtime started");
    }

    @Override
    public void onDisable() {
        if (commandRegistration != null) {
            commandRegistration.close();
            commandRegistration = null;
        }
        if (inworldListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(inworldListener);
            inworldListener = null;
        }
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        if (audiences != null) {
            audiences.close();
            audiences = null;
        }
    }

    /** @return the neutral runtime, or {@code null} while the plugin is off. */
    public SuiteRuntime runtime() {
        return runtime;
    }

    private void registerInWorld(SuiteRuntime created, PluginLogger logger,
                                 UserLanguageStore languages) {
        InWorldService handler = created.services().find(InWorldService.class).orElse(null);
        MessageRenderer renderer = created.services().find(MessageRenderer.class).orElse(null);
        if (handler == null || renderer == null) {
            logger.debug("In-world package is not active; listener not registered");
            return;
        }
        inworldListener = new SpigotInWorldListener(
                handler, renderer, created.messageDispatch(), getServer(), logger,
                languages, created.defaultLanguage());
        getServer().getPluginManager().registerEvents(inworldListener, this);
    }

    private CommandMap commandMap() {
        try {
            Field field = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            field.setAccessible(true);
            return (CommandMap) field.get(Bukkit.getServer());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access the Bukkit command map", failure);
        }
    }

    private PluginLogger logger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) { getLogger().info(format(m, a)); }
            @Override public void warn(String m, Object... a) { getLogger().warning(format(m, a)); }
            @Override public void error(String m, Object... a) { getLogger().severe(format(m, a)); }
            @Override public void error(String m, Throwable t) { getLogger().severe(format(m) + " :: " + t); }
            @Override public void debug(String m, Object... a) {
                if (isDebug()) {
                    getLogger().info("[debug] " + format(m, a));
                }
            }
        };
    }

    private static String format(String message, Object... args) {
        return args == null || args.length == 0 ? message : String.format(message, args);
    }

    private boolean isDebug() {
        return Boolean.getBoolean("textformattersuite.debug");
    }

    /**
     * First-boot experience: copies the bundled {@code defaults/} tree into the
     * canonical per-package configuration paths. Existing user files are never
     * overwritten.
     */
    private void copyDefaultsIfMissing(Path folder) {
        copyResourceIfMissing(folder, "defaults/config.yml",
                folder.resolve("config/suite/config.yml"), folder.resolve("config.yml"));
        for (String name : List.of("chat.global", "join", "quit", "death", "advancement")) {
            copyResourceIfMissing(folder, "defaults/channels/" + name + ".yml",
                    folder.resolve("config/textformatter/channels/" + name + ".yml"),
                    folder.resolve("channels/" + name + ".yml"));
        }
        copyResourceIfMissing(folder, "defaults/translators/google.yml",
                folder.resolve("config/chattranslator/translators/google.yml"),
                folder.resolve("translators/google.yml"));
        copyResourceIfMissing(folder, "defaults/messages.yml",
                folder.resolve("config/suite/messages.yml"), folder.resolve("messages.yml"));
        copyResourceIfMissing(folder, "defaults/sync/discord.yml",
                folder.resolve("config/sync-discord/config.yml"), folder.resolve("sync/discord.yml"));
        copyResourceIfMissing(folder, "defaults/sync/websocket.yml",
                folder.resolve("config/sync-websocket/config.yml"), folder.resolve("sync/websocket.yml"));
        copyResourceIfMissing(folder, "defaults/inworld.yml",
                folder.resolve("config/inworld/config.yml"), folder.resolve("inworld.yml"));
        copyResourceIfMissing(folder, "defaults/rules.yml",
                folder.resolve("config/iflow-firewall/rules.yml"), folder.resolve("rules.yml"));
    }

    private void copyResourceIfMissing(Path folder, String resourcePath, Path canonical, Path legacy) {
        if (Files.exists(canonical) || (legacy != null && Files.exists(legacy))) {
            return;
        }
        copyResource(folder, resourcePath, canonical);
    }

    private void copyResource(Path folder, String resourcePath, Path target) {
        if (Files.exists(target)) {
            return;
        }
        try (var in = getClass().getResourceAsStream("/" + resourcePath)) {
            if (in == null) {
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
            getLogger().info("Default file created: " + folder.relativize(target));
        } catch (IOException exception) {
            getLogger().warning("Cannot create " + target + ": " + exception.getMessage());
        }
    }
}

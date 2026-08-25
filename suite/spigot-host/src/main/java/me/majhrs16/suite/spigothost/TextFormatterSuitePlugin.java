package me.majhrs16.suite.spigothost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Channel;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.config.TranslatorsConfig;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Composition root of the TextFormatter Suite on Spigot: bootstraps
 * {@link SuiteHost} + {@link MessageDispatcher} from the suite file layout
 * and routes chat through the modern engine instead of the legacy core.
 *
 * <p>Threading: {@link AsyncPlayerChatEvent} fires off-main; translation,
 * routing and rendering run on that async thread and delivery hops back to
 * main inside {@link SpigotChatDelivery}. Vanilla broadcast is cancelled —
 * the engine owns every copy (echo + broadcast are independent messages,
 * echo gated by the channel's {@code show-sender}).</p>
 *
 * <p>Debug logging: launch with {@code -Dtextformattersuite.debug=true}
 * (JVM property, not hot-reloadable).</p>
 */
public final class TextFormatterSuitePlugin extends JavaPlugin implements Listener {

    /** Immutable wiring snapshot; swapped atomically on reload. */
    private static final class Runtime {
        final SuiteHost host;
        final MessageDispatcher dispatcher;
        final SpigotActorDirectory directory;

        Runtime(SuiteHost host, MessageDispatcher dispatcher, SpigotActorDirectory directory) {
            this.host = host;
            this.dispatcher = dispatcher;
            this.directory = directory;
        }
    }

    private BukkitAudiences audiences;
    private volatile Runtime runtime;

    @Override
    public void onEnable() {
        audiences = BukkitAudiences.create(this);
        reloadSuite();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TextFormatter Suite enabled (channels: "
            + runtime.host.channels().paths().size()
            + ", translator: " + runtime.host.translation().activeName() + ")");
    }

    @Override
    public void onDisable() {
        if (audiences != null) {
            audiences.close();
            audiences = null;
        }
        runtime = null;
    }

    /** Re-reads the whole file layout from disk (save → apply). */
    public void reloadSuite() {
        Path folder = getDataFolder().toPath();
        copyDefaultsIfMissing(folder);
        PluginLogger logger = logger();
        PermissionChecker permissions = this::hasPermission;
        TranslationService translation = new TranslationService(TranslatorsConfig.load(folder));
        SuiteHost reloaded = SuiteHost.bootstrap(folder, permissions, translation, logger);
        SpigotActorDirectory dirs = new SpigotActorDirectory();
        SpigotChatDelivery delivery = new SpigotChatDelivery(this, audiences);
        this.runtime = new Runtime(reloaded,
            new MessageDispatcher(reloaded, dirs, delivery, permissions, logger), dirs);
    }

    private boolean hasPermission(Actor actor, String permission) {
        if (permission == null) {
            return true;
        }
        Player player = actor == null ? null : actor.handle();
        return player != null && player.hasPermission(permission);
    }

    /**
     * First-boot experience: copies the bundled {@code defaults/} tree
     * (config.yml, channels/, translators/) into the data folder. Existing
     * user files are never overwritten.
     */
    private void copyDefaultsIfMissing(Path folder) {
        try (var stream = getClass().getResourceAsStream("/defaults/config.yml")) {
            if (stream == null) {
                return;
            }
        } catch (IOException ignored) {
            return;
        }
        copyResource(folder, "defaults/config.yml", folder.resolve("config.yml"));
        for (String name : List.of("chat.global")) {
            copyResource(folder, "defaults/channels/" + name + ".yml",
                folder.resolve("channels/" + name + ".yml"));
        }
        copyResource(folder, "defaults/translators/google.yml",
            folder.resolve("translators/google.yml"));
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
            getLogger().info("default creado: " + folder.relativize(target));
        } catch (IOException exception) {
            getLogger().warning("no se pudo crear " + target + ": " + exception.getMessage());
        }
    }

    private PluginLogger logger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) { getLogger().info(format(m, a)); }
            @Override public void warn(String m, Object... a) { getLogger().warning(format(m, a)); }
            @Override public void error(String m, Object... a) { getLogger().severe(format(m, a)); }
            @Override public void error(String m, Throwable t) { getLogger().severe(m + " :: " + t); }
            @Override public void debug(String m, Object... a) { if (isDebug()) getLogger().info("[debug] " + format(m, a)); }
        };
    }

    private boolean isDebug() {
        return Boolean.getBoolean("textformattersuite.debug");
    }

    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        String out = message;
        for (Object arg : args) {
            int braces = out.indexOf("{}");
            int percent = out.indexOf("%s");
            if (braces < 0 && percent < 0) {
                break;
            }
            int at = braces < 0 ? percent : (percent < 0 ? braces : Math.min(braces, percent));
            int skip = out.startsWith("{}", at) ? 2 : 2;
            out = out.substring(0, at) + arg + out.substring(at + skip);
        }
        return out;
    }

    // -- events -----------------------------------------------------------

    /** Claim-first: igual que el legacy (LOWEST) para ganar antes que otros plugins. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Runtime current = runtime;
        if (current == null || current.dispatcher == null) {
            return;
        }
        event.setCancelled(true);

        Actor sender = current.directory.actorOf(event.getPlayer());
        me.majhrs16.suite.textformatter.channel.Channel channel =
            current.host.channels().resolve(resolveChannel(current, sender));
        String channelPath = channel.name();

        // Dos unidades atómicas; el eco respeta show-sender del canal.
        if (channel.showSender()) {
            dispatch(current, MessageType.CHAT, sender, Direction.initiator(),
                channelPath, event.getMessage());
        }
        dispatch(current, MessageType.CHAT, sender, Direction.others(),
            channelPath, event.getMessage());
    }

    /**
     * MVP channel selection: first registered channel whose base permission
     * the sender holds (registry order); falls back to {@code chat}.
     * Richer per-source routing belongs to iFlow rules (F7+).
     */
    private String resolveChannel(Runtime current, Actor sender) {
        ChannelRegistry channels = current.host.channels();
        List<String> paths = channels.paths();
        for (String path : paths) {
            String permission = channels.get(path)
                .map(c -> c.permission())
                .orElse(null);
            if (permission == null || hasPermission(sender, permission)) {
                return path;
            }
        }
        return "chat";
    }

    private void dispatch(Runtime current, MessageType type, Actor sender,
                          Direction direction, String channelPath, String text) {
        Message message = Message.builder()
            .type(type)
            .sender(sender)
            .direction(direction.channel(Channel.CHAT))
            .text(text)
            .channel(channelPath)
            .build();
        current.dispatcher.dispatch(message);
    }

    // -- commands ----------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Runtime current = runtime;
        if (current == null || current.host == null) {
            sender.sendMessage("[suite] no inicializado");
            return true;
        }
        SuiteHost host = current.host;
        String sub = args.length == 0 ? "status" : args[0];
        switch (sub) {
            case "reload" -> {
                try {
                    reloadSuite();
                    Runtime fresh = runtime;
                    sender.sendMessage("[suite] recargado: "
                        + (fresh != null ? fresh.host.channels().paths().size() : 0)
                        + " canales, traductor '"
                        + (fresh != null ? fresh.host.translation().activeName() : "?") + "'");
                } catch (RuntimeException exception) {
                    sender.sendMessage("[suite] error al recargar: " + exception.getMessage());
                }
                return true;
            }
            case "status" -> {
                sender.sendMessage("[suite] canales: " + host.channels().paths());
                sender.sendMessage("[suite] traductor activo: "
                    + host.translation().activeName());
                sender.sendMessage("[suite] engine.parallel: " + host.config().engineParallel()
                    + " · sonido: " + host.config().soundEnabled());
                return true;
            }
            default -> {
                sender.sendMessage("[suite] uso: /suite <reload|status>");
                return true;
            }
        }
    }
}

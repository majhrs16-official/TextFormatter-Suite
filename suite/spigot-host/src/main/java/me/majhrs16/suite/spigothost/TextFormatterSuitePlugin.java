package me.majhrs16.suite.spigothost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Channel;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.config.MessagesConfig;
import me.majhrs16.suite.host.config.TranslatorsConfig;
import me.majhrs16.suite.host.config.YamlUserLanguageStore;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Composition root of the TextFormatter Suite on Spigot: bootstraps
 * {@link SuiteHost} + {@link MessageDispatcher} from the suite file layout
 * and routes events through the modern engine instead of the legacy core.
 *
 * <p>Threading: {@link AsyncPlayerChatEvent} fires off-main; translation,
 * routing and rendering run on that async thread and delivery hops back to
 * main inside {@link SpigotChatDelivery}. How the vanilla event is claimed
 * is configurable ({@code chat.claim-mode}: {@code cancel-event} or
 * {@code clear-recipients}); the engine owns every copy either way
 * (echo + broadcast are independent messages, echo gated by the channel's
 * {@code show-sender}).</p>
 *
 * <p>Non-chat events (join/quit/death) are dispatched when a channel with
 * the conventional name exists in the registry ({@code join}, {@code quit},
 * {@code death}): presence IS configuration.</p>
 *
 * <p>Per-user language persists in {@code storage.yml} via
 * {@link YamlUserLanguageStore}; {@code /suite lang} manages it. The value
 * {@code off} maps to {@link Language#AUTO}, which disables translation for
 * that user both as sender and as receiver.</p>
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
        final UserLanguageStore languages;

        Runtime(SuiteHost host, MessageDispatcher dispatcher,
                SpigotActorDirectory directory, UserLanguageStore languages) {
            this.host = host;
            this.dispatcher = dispatcher;
            this.directory = directory;
            this.languages = languages;
        }
    }

    private BukkitAudiences audiences;
    private volatile UserLanguageStore languageStore;
    private volatile Runtime runtime;
    private volatile MessagesConfig messages;

    /** Último recurso si falta una clave en messages.yml (todo editable ahí). */
    private static final Map<String, String> BUILT_IN_MESSAGES = Map.ofEntries(
        Map.entry("prefix", "[suite] "),
        Map.entry("not-initialized", "[suite] no inicializado"),
        Map.entry("usage", "[suite] uso: /suite <lang|reload|status|toggle|reset>"),
        Map.entry("enabled", "[suite] activo: {} canales, traductor '{}'"),
        Map.entry("reload-ok", "[suite] recargado: {} canales, traductor '{}'"),
        Map.entry("reload-error", "[suite] error al recargar: {}"),
        Map.entry("status.channels", "[suite] canales: {}"),
        Map.entry("status.translator", "[suite] traductor activo: {}"),
        Map.entry("status.knobs", "[suite] engine.parallel: {} · sonido: {} · claim: {}"),
        Map.entry("lang.current", "[suite] tu idioma: {}"),
        Map.entry("lang.updated", "[suite] idioma actualizado: {}"),
        Map.entry("lang.invalid", "[suite] valor inválido: usa auto | off | <código> (ej. es, en, zh-CN)"),
        Map.entry("lang.other-admin", "[suite] setear el idioma de otro requiere admin"),
        Map.entry("lang.player-offline", "[suite] jugador no conectado: {}"),
        Map.entry("lang.console", "[suite] consola no tiene idioma; usa /suite lang <jugador> <valor>"),
        Map.entry("toggle.current", "[suite] traducción: {}"),
        Map.entry("reset.ok", "[suite] configs restauradas (respaldo en backup/); storage.yml intacto"),
        Map.entry("reset.error", "[suite] error al restaurar: {}"));

    @Override
    public void onEnable() {
        audiences = BukkitAudiences.create(this);
        reloadSuite();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info(messages.format("enabled",
            runtime.host.channels().paths().size(),
            runtime.host.translation().activeName()));
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
        this.messages = MessagesConfig.load(folder, BUILT_IN_MESSAGES);
        TranslationService translation = new TranslationService(TranslatorsConfig.load(folder));
        SuiteHost reloaded = SuiteHost.bootstrap(folder, permissions, translation,
            new SpigotPlaceholderResolver(), logger);
        UserLanguageStore languages = languageStore;
        if (languages == null) {
            languages = new YamlUserLanguageStore(folder);
            languageStore = languages;
        }
        SpigotActorDirectory dirs = new SpigotActorDirectory(languages);
        SpigotChatDelivery delivery = new SpigotChatDelivery(this, audiences);
        this.runtime = new Runtime(reloaded,
            new MessageDispatcher(reloaded, dirs, delivery, permissions, logger),
            dirs, languages);
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
        copyResource(folder, "defaults/messages.yml", folder.resolve("messages.yml"));
    }

    /** /suite reset: mueve configs de usuario a backup/<ts>/ y regenera defaults. */
    private boolean resetConfigs(Path folder) {
        try {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path backupDir = folder.resolve("backup").resolve(stamp);
            for (String name : List.of("config.yml", "messages.yml")) {
                Path p = folder.resolve(name);
                if (Files.exists(p)) {
                    Files.createDirectories(backupDir);
                    Files.move(p, backupDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (String sub : List.of("channels", "translators")) {
                Path dirPath = folder.resolve(sub);
                if (Files.isDirectory(dirPath)) {
                    Path target = backupDir.resolve(sub);
                    Files.createDirectories(target.getParent());
                    Files.move(dirPath, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            copyDefaultsIfMissing(folder);
            return true;
        } catch (IOException exception) {
            getLogger().warning("reset falló: " + exception.getMessage());
            return false;
        }
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
        return MessagesConfig.substitute(message, args);
    }

    // -- events -----------------------------------------------------------

    /** Claim-first: igual que el legacy (LOWEST) para ganar antes que otros plugins. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Runtime current = runtime;
        if (current == null || current.dispatcher == null) {
            return;
        }
        switch (current.host.config().claimMode()) {
            case CANCEL_EVENT -> event.setCancelled(true);
            case CLEAR_RECIPIENTS -> event.getRecipients().clear();
        }

        Actor sender = current.directory.actorOf(event.getPlayer());
        me.majhrs16.suite.textformatter.channel.Channel channel =
            current.host.channels().resolve(resolveChannel(current, sender));
        String channelPath = channel.name();
        boolean senderOff = isOff(current, sender);

        // Dos unidades atómicas; el eco respeta show-sender del canal.
        // Emisor con lang off → su mensaje no se traduce para nadie.
        if (channel.showSender()) {
            dispatch(current, MessageType.CHAT, sender, Direction.initiator(),
                channelPath, event.getMessage(), !senderOff);
        }
        dispatch(current, MessageType.CHAT, sender, Direction.others(),
            channelPath, event.getMessage(), !senderOff);
    }

    /** Canal convencional {@code join}; presencia en el registro = activado. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Runtime current = runtime;
        if (current == null) {
            return;
        }
        dispatchTyped(current, MessageType.JOIN, "join",
            current.directory.actorOf(event.getPlayer()), event.getPlayer().getName());
    }

    /** Canal convencional {@code quit}. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Runtime current = runtime;
        if (current == null) {
            return;
        }
        dispatchTyped(current, MessageType.LEAVE, "quit",
            current.directory.actorOf(event.getPlayer()), event.getPlayer().getName());
    }

    /** Canal convencional {@code death}; %content% = mensaje vanilla de muerte. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Runtime current = runtime;
        if (current == null) {
            return;
        }
        String vanilla = event.getDeathMessage() == null
            ? event.getEntity().getName()
            : event.getDeathMessage();
        dispatchTyped(current, MessageType.DEATH, "death",
            current.directory.actorOf(event.getEntity()), vanilla);
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
        dispatch(current, type, sender, direction, channelPath, text, true);
    }

    private void dispatch(Runtime current, MessageType type, Actor sender,
                          Direction direction, String channelPath, String text,
                          boolean translate) {
        Message message = Message.builder()
            .type(type)
            .sender(sender)
            .direction(direction.channel(Channel.CHAT))
            .translate(translate)
            .text(text)
            .channel(channelPath)
            .build();
        current.dispatcher.dispatch(message);
    }

    /**
     * Eventos tipados (join/quit/death): solo se despachan si el canal
     * convencional existe en el registro (presencia = configuración).
     * Una única unidad ALL para todos; el emisor con idioma {@code off}
     * no traduce su mensaje.
     */
    private void dispatchTyped(Runtime current, MessageType type, String channelName,
                               Actor subject, String content) {
        if (!current.host.channels().has(channelName)) {
            return;
        }
        boolean senderOff = isOff(current, subject);
        Message message = Message.builder()
            .type(type)
            .sender(subject)
            .direction(Direction.all())
            .translate(!senderOff)
            .text(content)
            .channel(channelName)
            .build();
        current.dispatcher.dispatch(message);
    }

    /** @return whether this user disabled translation ({@code /suite lang off}). */
    private boolean isOff(Runtime current, Actor actor) {
        UUID uuid = actor == null ? null : actor.uuid();
        return uuid != null && UserLanguageStore.OFF.equalsIgnoreCase(
            current.languages.languageOf(uuid).orElse(null));
    }

    // -- commands ----------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Runtime current = runtime;
        if (current == null || current.host == null) {
            sender.sendMessage(messages.format("not-initialized"));
            return true;
        }
        SuiteHost host = current.host;
        String sub = args.length == 0 ? "status" : args[0];
        switch (sub) {
            case "reload" -> {
                try {
                    reloadSuite();
                    Runtime fresh = runtime;
                    sender.sendMessage(messages.format("reload-ok",
                        fresh != null ? fresh.host.channels().paths().size() : 0,
                        fresh != null ? fresh.host.translation().activeName() : "?"));
                } catch (RuntimeException exception) {
                    sender.sendMessage(messages.format("reload-error", exception.getMessage()));
                }
                return true;
            }
            case "lang" -> {
                return handleLang(current, sender, args);
            }
            case "toggle" -> {
                return handleToggle(current, sender, args);
            }
            case "reset" -> {
                if (sender instanceof Player p && !p.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage(messages.format("lang.other-admin"));
                    return true;
                }
                if (!sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage(messages.format("lang.other-admin"));
                    return true;
                }
                try {
                    if (resetConfigs(getDataFolder().toPath())) {
                        reloadSuite();
                        sender.sendMessage(messages.format("reset.ok"));
                    } else {
                        sender.sendMessage(messages.format("reset.error", "ver log"));
                    }
                } catch (RuntimeException exception) {
                    sender.sendMessage(messages.format("reset.error", exception.getMessage()));
                }
                return true;
            }
            case "status" -> {
                sender.sendMessage(messages.format("status.channels", host.channels().paths()));
                sender.sendMessage(messages.format("status.translator",
                    host.translation().activeName()));
                sender.sendMessage(messages.format("status.knobs",
                    host.config().engineParallel(), host.config().soundEnabled(),
                    host.config().claimMode().name().toLowerCase(Locale.ROOT)
                        .replace('_', '-')));
                return true;
            }
            default -> {
                sender.sendMessage(messages.format("usage"));
                return true;
            }
        }
    }

    /**
     * {@code /suite lang} — muestra tu ajuste.
     * {@code /suite lang <auto|off|código>} — setea el propio.
     * {@code /suite lang <jugador> <auto|off|código>} — admin.
     */
    private boolean handleLang(Runtime current, CommandSender sender, String[] args) {
        Player self = sender instanceof Player player ? player : null;
        if (self == null && args.length == 0) {
            sender.sendMessage(messages.format("lang.console"));
            return true;
        }
        if (args.length == 0) {
            String current2 = current.languages.languageOf(self.getUniqueId())
                .orElse(UserLanguageStore.AUTO);
            sender.sendMessage(messages.format("lang.current", displayValue(current2)));
            return true;
        }

        UUID target;
        String value;
        if (args.length == 1) {
            target = self == null ? null : self.getUniqueId();
            value = normalizeLangValue(args[0]);
        } else {
            if (self == null || !self.hasPermission("textformattersuite.admin")) {
                sender.sendMessage(messages.format("lang.other-admin"));
                return true;
            }
            Player other = getServer().getPlayerExact(args[0]);
            if (other == null) {
                sender.sendMessage(messages.format("lang.player-offline", args[0]));
                return true;
            }
            target = other.getUniqueId();
            value = normalizeLangValue(args[1]);
        }
        if (target == null || !isValidLangValue(value)) {
            sender.sendMessage(messages.format("lang.invalid"));
            return true;
        }
        current.languages.save(target, value);
        sender.sendMessage(messages.format("lang.updated", displayValue(value)));
        return true;
    }

    /**
     * {@code /suite toggle} — alterna tu traducción entre off y auto.
     * {@code /suite toggle <jugador>} — admin.
     */
    private boolean handleToggle(Runtime current, CommandSender sender, String[] args) {
        Player self = sender instanceof Player player ? player : null;
        UUID target;
        if (args.length == 0) {
            target = self == null ? null : self.getUniqueId();
        } else {
            boolean admin = self == null
                ? sender.hasPermission("textformattersuite.admin")
                : self.hasPermission("textformattersuite.admin");
            if (!admin) {
                sender.sendMessage(messages.format("lang.other-admin"));
                return true;
            }
            Player other = getServer().getPlayerExact(args[0]);
            if (other == null) {
                sender.sendMessage(messages.format("lang.player-offline", args[0]));
                return true;
            }
            target = other.getUniqueId();
        }
        if (target == null) {
            sender.sendMessage(messages.format("lang.console"));
            return true;
        }
        String now = current.languages.languageOf(target).orElse(UserLanguageStore.AUTO);
        String flipped = UserLanguageStore.OFF.equalsIgnoreCase(now)
            ? UserLanguageStore.AUTO
            : UserLanguageStore.OFF;
        current.languages.save(target, flipped);
        sender.sendMessage(messages.format("toggle.current",
            UserLanguageStore.OFF.equalsIgnoreCase(flipped) ? "off" : "auto"));
        return true;
    }

    private static String normalizeLangValue(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static boolean isValidLangValue(String value) {
        if (value.equalsIgnoreCase(UserLanguageStore.AUTO)
            || value.equalsIgnoreCase(UserLanguageStore.OFF)) {
            return true;
        }
        return me.majhrs16.suite.api.message.Language.of(value).isPresent();
    }

    private static String displayValue(String stored) {
        if (UserLanguageStore.OFF.equalsIgnoreCase(stored)) {
            return "off (sin traducción)";
        }
        if (UserLanguageStore.AUTO.equalsIgnoreCase(stored)) {
            return "auto (locale del cliente)";
        }
        return stored;
    }
}

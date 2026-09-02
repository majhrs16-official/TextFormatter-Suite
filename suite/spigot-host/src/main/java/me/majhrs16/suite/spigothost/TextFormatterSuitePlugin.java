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
import me.majhrs16.suite.spigothost.logic.ChannelSelector;
import me.majhrs16.suite.spigothost.logic.EventRules;
import me.majhrs16.suite.spigothost.logic.LangSetting;
import me.majhrs16.suite.messages.MessagesCatalog;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.util.Arrays;
import java.util.function.Consumer;

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
        final DiscordBridge bridge;
        final PluginLogger logger;

        Runtime(SuiteHost host, MessageDispatcher dispatcher,
                SpigotActorDirectory directory, UserLanguageStore languages,
                DiscordBridge bridge, PluginLogger logger) {
            this.host = host;
            this.dispatcher = dispatcher;
            this.directory = directory;
            this.languages = languages;
            this.bridge = bridge;
            this.logger = logger;
        }
    }

    private BukkitAudiences audiences;
    private volatile UserLanguageStore languageStore;
    private volatile Runtime runtime;
    private volatile MessagesConfig messages;

    @Override
    public void onEnable() {
        audiences = BukkitAudiences.create(this);
        reloadSuite();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info(MessagesCatalog.getInstance().format(Locale.ENGLISH, "enabled",
            runtime.host.channels().paths().size(),
            runtime.host.translation().activeName()));
    }

    @Override
    public void onDisable() {
        Runtime current = runtime;
        if (current != null && current.bridge != null) {
            current.bridge.stop();
        }
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
        // Validación estructural de config (Item 18 FASE 3)
        me.majhrs16.suite.spigothost.validator.ConfigValidator.validate(
            me.majhrs16.suite.host.config.ConfigLoader.loadConfig(folder),
            logger,
            folder);
        PermissionChecker permissions = this::hasPermission;
        this.messages = MessagesConfig.load(folder, MessagesCatalog.getInstance().getAllMessages());
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
        MessageDispatcher dispatcher =
            new MessageDispatcher(reloaded, dirs, delivery, permissions, logger);
        DiscordBridge previous = runtime == null ? null : runtime.bridge;
        if (previous != null) {
            previous.stop();
        }
        DiscordBridge bridge = DiscordBridge.create(folder, dispatcher, logger);
        this.runtime = new Runtime(reloaded, dispatcher, dirs, languages, bridge, logger);
        if (bridge != null) {
            bridge.start();
        }
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
        for (String name : List.of("chat.global", "join", "quit", "death", "advancement")) {
            copyResource(folder, "defaults/channels/" + name + ".yml",
                folder.resolve("channels/" + name + ".yml"));
        }
        copyResource(folder, "defaults/translators/google.yml",
            folder.resolve("translators/google.yml"));
        copyResource(folder, "defaults/messages.yml", folder.resolve("messages.yml"));
        copyResource(folder, "defaults/sync/discord.yml",
            folder.resolve("sync/discord.yml"));
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
            @Override public void info(String m, Object... a) { getLogger().info(MessagesCatalog.getInstance().format(Locale.ENGLISH, m, a)); }
            @Override public void warn(String m, Object... a) { getLogger().warning(MessagesCatalog.getInstance().format(Locale.ENGLISH, m, a)); }
            @Override public void error(String m, Object... a) { getLogger().severe(MessagesCatalog.getInstance().format(Locale.ENGLISH, m, a)); }
            @Override public void error(String m, Throwable t) { getLogger().severe(MessagesCatalog.getInstance().format(Locale.ENGLISH, m) + " :: " + t); }
            @Override public void debug(String m, Object... a) { if (isDebug()) getLogger().info("[debug] " + MessagesCatalog.getInstance().format(Locale.ENGLISH, m, a)); }
        };
    }

    private boolean isDebug() {
        return Boolean.getBoolean("textformattersuite.debug");
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
            current.host.channels().resolve(
                ChannelSelector.select(List.copyOf(current.host.channels().all()),
                    permission -> hasPermission(sender, permission)));
        String channelPath = channel.name();
        boolean senderOff = isOff(current, sender);

        // Dos unidades atómicas; el eco respeta show-sender del canal.
        // Emisor con lang off → su mensaje no se traduce para nadie.
        if (channel.showSender()) {
            dispatch(current, MessageType.CHAT, sender, Direction.initiator(),
                channelPath, event.getMessage(), !senderOff);
        }
        Message broadcast = broadcast(current, MessageType.CHAT, sender,
            channelPath, event.getMessage(), !senderOff);
        mirror(current, broadcast);
    }

    private Message broadcast(Runtime current, MessageType type, Actor sender,
                              String channelPath, String text, boolean translate) {
        Message message = Message.builder()
            .type(type)
            .sender(sender)
            .direction(Direction.others().channel(Channel.CHAT))
            .translate(translate)
            .text(text)
            .channel(channelPath)
            .build();
        current.dispatcher.dispatch(message);
        return message;
    }

    private void mirror(Runtime current, Message sent) {
        if (current.bridge != null) {
            current.bridge.mirror(sent);
        }
    }

    /** Canal convencional {@code join}; presencia en el registro = activado. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Runtime current = runtime;
        if (current == null) {
            return;
        }
        dispatchTyped(current, MessageType.JOIN, EventRules.CHANNEL_JOIN,
            current.directory.actorOf(event.getPlayer()), event.getPlayer().getName());
    }

    /** Canal convencional {@code quit}. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Runtime current = runtime;
        if (current == null) {
            return;
        }
        dispatchTyped(current, MessageType.LEAVE, EventRules.CHANNEL_QUIT,
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
        dispatchTyped(current, MessageType.DEATH, EventRules.CHANNEL_DEATH,
            current.directory.actorOf(event.getEntity()), vanilla);
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
        if (!EventRules.typedEventEnabled(current.host.channels(), channelName)) {
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
        mirror(current, message);
    }

    /** @return whether this user disabled translation ({@code /suite lang off}). */
    private boolean isOff(Runtime current, Actor actor) {
        return !EventRules.shouldTranslate(current.languages,
            actor == null ? null : actor.uuid());
    }

    // -- commands ----------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Runtime current = runtime;
        if (current == null || current.host == null) {
            sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "not-initialized"));
            return true;
        }
        SuiteHost host = current.host;
        String sub = args.length == 0 ? "status" : args[0];
        switch (sub) {
            case "reload" -> {
                try {
                    reloadSuite();
                    Runtime fresh = runtime;
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "reload-ok",
                        fresh != null ? fresh.host.channels().paths().size() : 0,
                        fresh != null ? fresh.host.translation().activeName() : "?"));
                } catch (RuntimeException exception) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "reload-error", exception.getMessage()));
                }
                return true;
            }
            case "lang" -> {
                return handleLang(current, sender, Arrays.copyOfRange(args, 1, args.length));
            }
            case "toggle" -> {
                return handleToggle(current, sender, Arrays.copyOfRange(args, 1, args.length));
            }
            case "reset" -> {
                if (sender instanceof Player p && !p.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.other-admin"));
                    return true;
                }
                if (!sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.other-admin"));
                    return true;
                }
                try {
                    if (resetConfigs(getDataFolder().toPath())) {
                        reloadSuite();
                        sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "reset.ok"));
                    } else {
                        sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "reset.error", "ver log"));
                    }
                } catch (RuntimeException exception) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "reset.error", exception.getMessage()));
                }
                return true;
            }
            case "status" -> {
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "status.channels", host.channels().paths()));
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "status.translator",
                    host.translation().activeName()));
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "status.knobs",
                    host.config().engineParallel(), host.config().soundEnabled(),
                    host.config().claimMode().name().toLowerCase(Locale.ROOT)
                        .replace('_', '-')));
                return true;
            }
            case "test" -> {
                return handleTest(current, sender, Arrays.copyOfRange(args, 1, args.length));
            }
            default -> {
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "usage"));
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
            sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.console"));
            return true;
        }
        if (args.length == 0) {
            String current2 = LangSetting.normalize(current.languages
                .languageOf(self.getUniqueId()).orElse(MessagesCatalog.getInstance().format(Locale.ENGLISH, "auto")));
            sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.current", LangSetting.display(current2)));
            return true;
        }

        UUID target;
        String value;
        if (args.length == 1) {
            target = self == null ? null : self.getUniqueId();
            value = LangSetting.normalize(args[0]);
        } else {
            if (self == null || !self.hasPermission("textformattersuite.admin")) {
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.other-admin"));
                return true;
            }
            Player other = getServer().getPlayerExact(args[0]);
            if (other == null) {
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.player-offline", args[0]));
                return true;
            }
            target = other.getUniqueId();
            value = LangSetting.normalize(args[1]);
        }
        if (target == null || !LangSetting.isValid(value)) {
            sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.invalid"));
            return true;
        }
        current.languages.save(target, value);
        sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.updated", LangSetting.display(value)));
        return true;
    }

    /**
     * {@code /suite toggle} — alterna tu traducción entre off y auto.
     * {@code /suite toggle <jugador>} — admin.
     */
    private boolean handleToggle(Runtime current, CommandSender sender, String[] args) {
        Player self = sender instanceof Player player ? player : null;
        UUID target;
        String value;
        if (args.length == 0) {
            target = self == null ? null : self.getUniqueId();
        } else {
            boolean admin = self == null
                ? sender.hasPermission("textformattersuite.admin")
                : self.hasPermission("textformattersuite.admin");
            if (!admin) {
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.other-admin"));
                return true;
            }
            Player other = getServer().getPlayerExact(args[0]);
            if (other == null) {
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.player-offline", args[0]));
                return true;
            }
            target = other.getUniqueId();
            value = LangSetting.normalize(args[1]);
        }
        if (target == null) {
            sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.console"));
            return true;
        }
        String flipped = LangSetting.flip(
            current.languages.languageOf(target).orElse(MessagesCatalog.getInstance().format(Locale.ENGLISH, "auto")));
        current.languages.save(target, flipped);
        sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "toggle.current",
            LangSetting.display(flipped).startsWith("off") ? "off" : "auto"));
        return true;

    }

    /**
     * {@code /suite test} — ejecuta la suite de tests automatizada en runtime.
     * {@code /suite test full} — suite completa (default).
     * {@code /suite test stress <players> <msgs>} — stress test.
     * {@code /suite test concurrency <threads> <msgs>} — test de concurrencia.
     * {@code /suite test routing} — tests de enrutamiento.
     * {@code /suite test events} — tests de eventos.
     * {@code /suite test perf} — profiling de hot paths.
     * {@code /suite test sync} — tests de sinks de sincronización.
     */
    private boolean handleTest(Runtime current, CommandSender sender, String[] args) {
        if (!sender.hasPermission("textformattersuite.admin")) {
            sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.other-admin"));
            return true;
        }

        var testService = getTestService();
        if (testService == null) {
            sender.sendMessage("§cTest service not available. Is tester module loaded?");
            return true;
        }

        String sub = args.length == 0 ? "full" : args[0];

        sender.sendMessage("§a[Test] Starting: " + sub);

        // Run async to not block main thread
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            var reporter = (Consumer<String>) line -> {
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(line));
            };

            try {
                switch (sub) {
                    case "full" -> testService.runFullTestSuite(reporter);
                    case "stress" -> {
                        int players = args.length > 1 ? Integer.parseInt(args[1]) : 5;
                        int msgs = args.length > 2 ? Integer.parseInt(args[2]) : 20;
                        reporter.accept("§6[TEST] Stress Test (" + players + " players, " + msgs + " msgs each)...");
                        testService.runStressTest(players, msgs);
                        reporter.accept("§a[PASS] Stress Test complete");
                    }
                    case "concurrency" -> {
                        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 10;
                        int msgs = args.length > 2 ? Integer.parseInt(args[2]) : 20;
                        reporter.accept("§6[TEST] Concurrency Test (" + threads + " threads, " + msgs + " msgs each)...");
                        testService.runConcurrencyTest(threads, msgs);
                        reporter.accept("§a[PASS] Concurrency Test complete");
                    }
                    default -> sender.sendMessage("§cUnknown test: " + sub + ". Use: full, stress, concurrency");
                }
            } catch (Exception e) {
                sender.sendMessage("§c[Test] Error: " + e.getMessage());
                Runtime rt = runtime;
                if (rt != null && rt.logger != null) {
                    rt.logger.error("Test error: " + sub, e);
                }
            }
        });
        return true;
    }

    private me.majhrs16.suite.tester.TestService getTestService() {
        // Get TestService from current runtime
        Runtime rt = runtime;
        if (rt != null && rt.host != null && rt.dispatcher != null) {
            return new me.majhrs16.suite.tester.TestService(
                rt.host,
                rt.dispatcher,
                rt.directory,
                rt.languages,
                rt.logger
            );
        }
        return null;
    }

}

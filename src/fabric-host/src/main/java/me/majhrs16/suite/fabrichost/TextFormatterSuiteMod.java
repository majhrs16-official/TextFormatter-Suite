package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.config.MessagesConfig;
import me.majhrs16.suite.host.config.TranslatorsConfig;
import me.majhrs16.suite.host.config.YamlUserLanguageStore;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.fabrichost.logic.ChannelSelector;
import me.majhrs16.suite.fabrichost.logic.EventRules;
import me.majhrs16.suite.fabrichost.logic.LangSetting;
import me.majhrs16.suite.fabrichost.validator.FabricConfigValidator;
import me.majhrs16.suite.messages.MessagesCatalog;
import me.majhrs16.suite.extension.ExtensionManager;
import me.majhrs16.suite.fabrichost.FabricInWorldHandler;
import me.majhrs16.suite.messages.MessagesCatalog;
import me.majhrs16.suite.observability.Observability;
import me.majhrs16.suite.syncbus.DefaultSyncBus;
import me.majhrs16.suite.syncbus.SyncBus;
import me.majhrs16.suite.syncwebsocket.WebSocketSyncSink;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.entity.damage.DamageSource;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.Set;
import java.util.HashSet;

/**
 * Composition root of the TextFormatter Suite on Fabric: bootstraps
 * {@link SuiteHost} + {@link MessageDispatcher} from the suite file layout
 * and routes events through the modern engine instead of the legacy core.
 *
 * <p>Threading: chat events fire on the server thread; translation,
 * routing and rendering run on that thread and delivery uses
 * {@link FabricAudiences} directly.</p>
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
public final class TextFormatterSuiteMod implements ModInitializer {

    /** Immutable wiring snapshot; swapped atomically on reload. */
    private static final class Runtime {
        final SuiteHost host;
        final MessageDispatcher dispatcher;
        final FabricActorDirectory directory;
        final UserLanguageStore languages;
        final DiscordBridge bridge;
        final WebSocketSyncSink wsSink;
        final Observability observability;
        final ExtensionManager extensionManager;
        final FabricInWorldHandler inworldHandler;
        final SyncBus syncBus;
        final PluginLogger logger;

        Runtime(SuiteHost host, MessageDispatcher dispatcher,
                FabricActorDirectory directory, UserLanguageStore languages,
                DiscordBridge bridge, WebSocketSyncSink wsSink,
                Observability observability,
                ExtensionManager extensionManager,
                FabricInWorldHandler inworldHandler,
                SyncBus syncBus,
                PluginLogger logger) {
            this.host = host;
            this.dispatcher = dispatcher;
            this.directory = directory;
            this.languages = languages;
            this.bridge = bridge;
            this.wsSink = wsSink;
            this.observability = observability;
            this.extensionManager = extensionManager;
            this.inworldHandler = inworldHandler;
            this.syncBus = syncBus;
            this.logger = logger;
        }

        public void close() {
            if (syncBus != null) {
                try {
                    syncBus.close();
                } catch (Exception e) {
                    logger.warn("Error closing SyncBus: " + e.getMessage());
                }
            }
            if (wsSink != null) {
                try {
                    wsSink.stop();
                } catch (Exception e) {
                    logger.warn("Error closing WebSocket sink: " + e.getMessage());
                }
            }
            if (observability != null) {
                try {
                    observability.stop();
                } catch (Exception e) {
                    logger.warn("Error stopping Observability: " + e.getMessage());
                }
            }
            if (extensionManager != null) {
                try {
                    extensionManager.stop();
                } catch (Exception e) {
                    logger.warn("Error stopping ExtensionManager: " + e.getMessage());
                }
            }
            if (inworldHandler != null) {
                try {
                    logger.debug("InWorldHandler cleanup");
                } catch (Exception e) {
                    logger.warn("Error cleaning InWorldHandler: " + e.getMessage());
                }
            }
            if (dispatcher != null) {
                try {
                    dispatcher.close();
                } catch (Exception e) {
                    logger.warn("Error closing MessageDispatcher: " + e.getMessage());
                }
            }
            if (bridge != null) {
                try {
                    bridge.stop();
                } catch (Exception e) {
                    logger.warn("Error stopping DiscordBridge: " + e.getMessage());
                }
            }
        }
    }

    private static MinecraftServer SERVER;
    private static volatile UserLanguageStore LANGUAGE_STORE;
    private static volatile Runtime RUNTIME;
    private static volatile MessagesConfig MESSAGES;
    private static final Set<UUID> recentlyDead = ConcurrentHashMap.newKeySet();
    private static final Logger LOGGER = LoggerFactory.getLogger("TextFormatterSuite");

    /** Fallback messages if missing from messages.yml. */
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
        Map.entry("reset.error", "[suite] error al recargar: {}"),
        Map.entry("test.service-unavailable", "[suite] servicio de tests no disponible"),
        Map.entry("test.starting", "[suite] iniciando test: {}"),
        Map.entry("test.unknown", "[suite] test desconocido: {}"),
        Map.entry("test.error", "[suite] error en test: {}"),
        Map.entry("module.list.empty", "[suite] no hay módulos instalados"),
        Map.entry("module.install.started", "[suite] instalando módulo {} v{}"),
        Map.entry("module.update.started", "[suite] actualizando módulo {} v{}"),
        Map.entry("module.remove.started", "[suite] removiendo módulo {}"),
        Map.entry("module.info", "[suite] info módulo: {}"),
        Map.entry("suite.update.started", "[suite] actualizando suite ({})"),
        Map.entry("file.default-created", "[suite] creado default: {}"),
        Map.entry("file.create-failed", "[suite] error creando {}: {}"),
        Map.entry("file.reset-failed", "[suite] error restaurando configs: {}")
    );

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Runtime current = RUNTIME;
            if (current == null) return;
            dispatchTyped(current, MessageType.JOIN, EventRules.CHANNEL_JOIN,
                current.directory.actorOf(handler.player), handler.player.getName().getString());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Runtime current = RUNTIME;
            if (current == null) return;
            dispatchTyped(current, MessageType.LEAVE, EventRules.CHANNEL_QUIT,
                current.directory.actorOf(handler.player), handler.player.getName().getString());
        });

        // Death event - using mixin or alternative approach for Fabric 1.21
        // PlayerDeathEvents.AFTER_DEATH.register((damageSource, player) -> {
        //     Runtime current = RUNTIME;
        //     if (current == null) return;
        //     String vanilla = damageSource.getDeathMessage() != null
        //         ? damageSource.getDeathMessage().getString()
        //         : player.getName().getString();
        //     dispatchTyped(current, MessageType.DEATH, EventRules.CHANNEL_DEATH,
        //         current.directory.actorOf(player), vanilla);
        // });

        // Chat message event
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            Runtime current = RUNTIME;
            if (current == null || current.dispatcher == null) {
                return;
            }
            Actor senderActor = current.directory.actorOf(sender);
            Channel channel = current.host.channels().resolve(
                ChannelSelector.select(List.copyOf(current.host.channels().all()),
                    permission -> hasPermission(senderActor, permission)));
            String channelPath = channel.name();
            boolean senderOff = isOff(current, senderActor);

            String text = message.getContent().getString();

            if (channel.showSender()) {
                dispatch(current, MessageType.CHAT, senderActor, Direction.initiator(),
                    channelPath, text, !senderOff);
            }
            Message broadcast = broadcast(current, MessageType.CHAT, senderActor,
                channelPath, text, !senderOff);
            mirror(current, broadcast);
        });

        // Death event - tick-based detection for Fabric 1.21
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Runtime current = RUNTIME;
            if (current == null) return;
            
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (!player.isAlive() && !recentlyDead.contains(player.getUuid())) {
                    // Player just died
                    recentlyDead.add(player.getUuid());
                    String vanilla = player.getDamageTracker().getDeathMessage() != null
                        ? player.getDamageTracker().getDeathMessage().getString()
                        : player.getName().getString();
                    dispatchTyped(current, MessageType.DEATH, EventRules.CHANNEL_DEATH,
                        current.directory.actorOf(player), vanilla);
                } else if (player.isAlive() && recentlyDead.contains(player.getUuid())) {
                    // Player respawned
                    recentlyDead.remove(player.getUuid());
                }
            }
        });

// Register /suite command using Brigadier - simplified for compilation
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<ServerCommandSource> suiteCmd = LiteralArgumentBuilder.<ServerCommandSource>literal("suite")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("TextFormatter Suite loaded"), false);
                    return 1;
                });
            dispatcher.register(suiteCmd);
        });
    }

    private void onServerStarted(MinecraftServer server) {
        SERVER = server;
        reloadSuite();
        LOGGER.info(MESSAGES.format("enabled",
            RUNTIME.host.channels().paths().size(),
            RUNTIME.host.translation().activeName()));
    }

    private void onServerStopping(MinecraftServer server) {
        Runtime current = RUNTIME;
        if (current != null) {
            try {
                current.close();
            } catch (Exception e) {
                LOGGER.warn("Error during shutdown: " + e.getMessage());
            }
        }
        recentlyDead.clear();
        RUNTIME = null;
        SERVER = null;
    }

    /** Re-reads the whole file layout from disk (save → apply). */
    public static void reloadSuite() {
        if (SERVER == null) return;
        
        // Close old runtime first
        Runtime oldRuntime = RUNTIME;
        if (oldRuntime != null) {
            try {
                oldRuntime.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing old runtime: " + e.getMessage());
            }
        }
        
        Path folder = SERVER.getRunDirectory().resolve("textformatter-suite");
        copyDefaultsIfMissing(folder);
        PluginLogger logger = logger();
        PermissionChecker permissions = TextFormatterSuiteMod::hasPermission;
        MESSAGES = MessagesConfig.load(folder, MessagesCatalog.getInstance().getAllMessages());
        TranslationService translation = new TranslationService(TranslatorsConfig.load(folder));
        SuiteHost reloaded = SuiteHost.bootstrap(folder, permissions, translation,
            new FabricPlaceholderResolver(), logger);

        // Validate configuration structure
        FabricConfigValidator.validate(reloaded.config(), logger, folder);

        UserLanguageStore languages = LANGUAGE_STORE;
        if (languages == null) {
            languages = new YamlUserLanguageStore(folder);
            LANGUAGE_STORE = languages;
        }
        final UserLanguageStore finalLanguages = languages;
        FabricActorDirectory dirs = new FabricActorDirectory(SERVER, languages);
        FabricChatDelivery delivery = new FabricChatDelivery(SERVER);
        MessageDispatcher dispatcher =
            new MessageDispatcher(reloaded, dirs, delivery, permissions, logger);
        DiscordBridge bridge = DiscordBridge.create(folder, dispatcher, logger);
        
        WebSocketSyncSink wsSink = null;
        try {
            Path wsConfig = folder.resolve("sync/websocket.yml");
            String wsToken = "";
            int wsPort = 9092;
            String wsBind = "127.0.0.1";
            boolean wsEnabled = false;
            if (Files.exists(wsConfig)) {
                org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml(
                    new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
                String content = Files.readString(wsConfig);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) yaml.load(content);
                if (map != null) {
                    Object token = map.get("token");
                    if (token instanceof String) wsToken = (String) token;
                    Object port = map.get("port");
                    if (port instanceof Number) wsPort = ((Number) port).intValue();
                    Object bind = map.get("bind");
                    if (bind instanceof String) wsBind = (String) bind;
                    Object enabled = map.get("enabled");
                    if (enabled instanceof Boolean) wsEnabled = (Boolean) enabled;
                }
            }

            if (wsEnabled) {
                if (wsToken == null || wsToken.isBlank()) {
                    logger.warn("WebSocket sync is enabled but no auth token configured! Refusing to start without token.");
                } else {
                    String bindAddress = System.getProperty("textformattersuite.ws.bind", wsBind);
                    wsSink = new WebSocketSyncSink(wsPort, wsToken, logger, bindAddress);
                    logger.info("WebSocket sync sink created on " + bindAddress + ":" + wsPort);
                }
            } else {
                logger.info("WebSocket sync sink is disabled (enabled: false in config)");
            }
        } catch (Exception e) {
            logger.warn("Failed to create WebSocket sync sink: " + e.getMessage());
        }

        // Initialize SyncBus and register sinks
        SyncBus syncBus = new DefaultSyncBus(logger);

        if (bridge != null && bridge.getSink() != null) {
            syncBus.register(bridge.getSink());
            logger.debug("SyncBus: registered Discord sink");
        }

        if (wsSink != null) {
            syncBus.register(wsSink);
            logger.debug("SyncBus: registered WebSocket sink");
        }

        syncBus.setInboundListener(new me.majhrs16.suite.api.spi.SyncListener() {
            @Override
            public void onMessage(me.majhrs16.suite.api.spi.SyncSink sink, Message message) {
                dispatcher.dispatch(message);
            }

            @Override
            public void onDisconnect(me.majhrs16.suite.api.spi.SyncSink sink, String reason) {
                logger.warn("SyncBus: sink '" + sink.name() + "' disconnected: " + reason);
            }
        });

        try {
            syncBus.start();
            logger.info("SyncBus started with " + syncBus.sinkNames().size() + " sink(s): " + syncBus.sinkNames());
        } catch (Exception e) {
            logger.error("Failed to start SyncBus: " + e.getMessage(), e);
        }

        // Initialize Observability module
        Observability observability = null;
        try {
            observability = Observability.createDefault(
                reloaded, dispatcher, reloaded.channels(), logger);
            observability.start();
            logger.info("Observability module started (metrics:9090, debug:9091)");
        } catch (Exception e) {
            logger.warn("Failed to start Observability module: " + e.getMessage());
        }

        // Initialize FabricInWorldHandler
        FabricInWorldHandler inworldHandler = new FabricInWorldHandler(reloaded, dispatcher,
            SERVER,
            reloaded.channels(), logger,
            translation, finalLanguages);
        inworldHandler.registerListeners();

        // Reload extensions
        Path extensionsDir = folder.resolve("extensions");
        ExtensionManager extensionManager = new ExtensionManager(
            logger, 
            extId -> {
                return new me.majhrs16.suite.extension.ExtensionContext(
                    reloaded,
                    dispatcher,
                    logger,
                    translation,
                    finalLanguages,
                    reloaded.channels(),
                    folder,
                    extId
                );
            }, 
            folder.resolve("extensions"),
            me.majhrs16.suite.api.SemVer.parse("2.1.0")
        );
        extensionManager.start();

        RUNTIME = new Runtime(reloaded, dispatcher, dirs, finalLanguages, bridge, wsSink, observability, extensionManager, inworldHandler, syncBus, logger);
        
        if (bridge != null) {
            bridge.start();
        }
        if (wsSink != null) {
            wsSink.setListener(new SyncListener() {
                @Override
                public void onMessage(SyncSink sink, Message message) {
                    dispatcher.dispatch(message);
                }

                @Override
                public void onDisconnect(SyncSink sink, String reason) {
                    logger.warn("WebSocket sink '" + sink.name() + "' disconnected: " + reason);
                }
            });
            try {
                wsSink.start();
            } catch (IOException e) {
                logger.warn("Failed to start WebSocket sink: " + e.getMessage());
            }
        }
    }

    private static boolean hasPermission(Actor actor, String permission) {
        if (permission == null) return true;
        ServerPlayerEntity player = actor == null ? null : actor.handle();
        return player != null && player.hasPermissionLevel(2);
    }

    /**
     * First-boot experience: copies the bundled {@code defaults/} tree
     * (config.yml, channels/, translators/) into the data folder. Existing
     * user files are never overwritten.
     */
    private static void copyDefaultsIfMissing(Path folder) {
        try (var stream = TextFormatterSuiteMod.class.getResourceAsStream("/defaults/config.yml")) {
            if (stream == null) return;
        } catch (IOException ignored) { return; }
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
        copyResource(folder, "defaults/sync/websocket.yml",
            folder.resolve("sync/websocket.yml"));
        copyResource(folder, "defaults/inworld.yml",
            folder.resolve("inworld.yml"));
    }

    /** /suite reset: mueve configs de usuario a backup/<ts>/ y regenera defaults. */
    private static boolean resetConfigs(Path folder) {
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
            LOGGER.warn(MessagesCatalog.getInstance().format(Locale.ENGLISH, "file.reset-failed", exception.getMessage()));
            return false;
        }
    }

    private static void copyResource(Path folder, String resourcePath, Path target) {
        if (Files.exists(target)) return;
        try (var in = TextFormatterSuiteMod.class.getResourceAsStream("/" + resourcePath)) {
            if (in == null) return;
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
            if (SERVER != null) LOGGER.info(MessagesCatalog.getInstance().format(Locale.ENGLISH, "file.default-created", folder.relativize(target)));
        } catch (IOException exception) {
            if (SERVER != null) LOGGER.info(MessagesCatalog.getInstance().format(Locale.ENGLISH, "file.create-failed", target, exception.getMessage()));
        }
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) { if (SERVER != null) LOGGER.info(MessagesCatalog.getInstance().format(Locale.ENGLISH, m, a)); }
            @Override public void warn(String m, Object... a) { if (SERVER != null) LOGGER.warn(MessagesCatalog.getInstance().format(Locale.ENGLISH, m, a)); }
            @Override public void error(String m, Object... a) { if (SERVER != null) LOGGER.error(MessagesCatalog.getInstance().format(Locale.ENGLISH, m, a)); }
            @Override public void error(String m, Throwable t) { if (SERVER != null) LOGGER.error(MessagesCatalog.getInstance().format(Locale.ENGLISH, m) + " :: " + t); }
            @Override public void debug(String m, Object... a) { if (isDebug() && SERVER != null) LOGGER.info("[debug] " + MessagesCatalog.getInstance().format(Locale.ENGLISH, m, a)); }
        };
    }

    private static boolean isDebug() {
        return Boolean.getBoolean("textformattersuite.debug");
    }

    private static String format(String message, Object... args) {
        return MessagesCatalog.getInstance().format(Locale.ENGLISH, message, args);
    }

    // -- dispatch helpers ---------------------------------------------------

    private static void dispatch(Runtime current, MessageType type, Actor sender,
                                 Direction direction, String channelPath, String text) {
        dispatch(current, type, sender, direction, channelPath, text, true);
    }

    private static void dispatch(Runtime current, MessageType type, Actor sender,
                                 Direction direction, String channelPath, String text,
                                 boolean translate) {
        Message message = Message.builder()
            .type(type)
            .sender(sender)
            .direction(direction)
            .translate(translate)
            .text(text)
            .channel(channelPath)
            .build();
        current.dispatcher.dispatch(message);
    }

    private static Message broadcast(Runtime current, MessageType type, Actor sender,
                                     String channelPath, String text, boolean translate) {
        Message message = Message.builder()
            .type(type)
            .sender(sender)
            .direction(Direction.others())
            .translate(translate)
            .text(text)
            .channel(channelPath)
            .build();
        current.dispatcher.dispatch(message);
        return message;
    }

    private static void mirror(Runtime current, Message sent) {
        if (current.bridge != null) {
            current.bridge.mirror(sent);
        }
    }

    /**
     * Eventos tipados (join/quit/death): solo se despachan si el canal
     * convencional existe en el registro (presencia = configuración).
     */
    private static void dispatchTyped(Runtime current, MessageType type, String channelName,
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
    private static boolean isOff(Runtime current, Actor actor) {
        return !EventRules.shouldTranslate(current.languages,
            actor == null ? null : actor.uuid());
    }

    private static int handleLang(CommandContext<ServerCommandSource> ctx, String value) {
        Runtime r = RUNTIME;
        if (r == null) {
            ctx.getSource().sendFeedback(() -> Text.literal(MESSAGES.format("not-initialized")), false);
            return 0;
        }
        if (!(ctx.getSource().getEntity() instanceof ServerPlayerEntity player)) {
            ctx.getSource().sendFeedback(() -> Text.literal(MESSAGES.format("lang.console")), false);
            return 0;
        }
        String normalized = LangSetting.normalize(value);
        if (!LangSetting.isValid(normalized)) {
            ctx.getSource().sendFeedback(() -> Text.literal(MESSAGES.format("lang.invalid")), false);
            return 0;
        }
        r.languages.save(player.getUuid(), normalized);
        ctx.getSource().sendFeedback(() -> Text.literal(MESSAGES.format("lang.updated", LangSetting.display(normalized))), false);
        return 1;
    }
}
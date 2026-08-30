package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.message.Actor;
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
import me.majhrs16.suite.fabrichost.logic.ChannelSelector;
import me.majhrs16.suite.fabrichost.logic.EventRules;
import me.majhrs16.suite.fabrichost.logic.LangSetting;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

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

/**
 * Composition root of the TextFormatter Suite on Fabric.
 */
public final class TextFormatterSuiteMod implements ModInitializer {

    /** Immutable wiring snapshot; swapped atomically on reload. */
    private static final class Runtime {
        final SuiteHost host;
        final MessageDispatcher dispatcher;
        final FabricActorDirectory directory;
        final UserLanguageStore languages;
        final DiscordBridge bridge;

        Runtime(SuiteHost host, MessageDispatcher dispatcher,
                FabricActorDirectory directory, UserLanguageStore languages,
                DiscordBridge bridge) {
            this.host = host;
            this.dispatcher = dispatcher;
            this.directory = directory;
            this.languages = languages;
            this.bridge = bridge;
        }
    }

    private static MinecraftServer SERVER;
    private static volatile UserLanguageStore LANGUAGE_STORE;
    private static volatile Runtime RUNTIME;
    private static volatile MessagesConfig MESSAGES;
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
        Map.entry("reset.error", "[suite] error al recargar: {}"));

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

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            Runtime current = RUNTIME;
            if (current == null || current.dispatcher == null) return;

            Actor senderActor = current.directory.actorOf(sender);
            String channelPath = ChannelSelector.select(List.copyOf(current.host.channels().all()),
                    permission -> hasPermission(senderActor, permission));
            Channel channel = current.host.channels().resolve(channelPath);
            boolean senderOff = isOff(current, senderActor);

            String text = message.getSignedContent();

            if (channel.showSender()) {
                dispatch(current, MessageType.CHAT, senderActor, me.majhrs16.suite.api.message.Direction.initiator(),
                    channelPath, text, !senderOff);
            }
            Message broadcast = broadcast(current, MessageType.CHAT, senderActor,
                channelPath, text, !senderOff);
            mirror(current, broadcast);
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
        if (current != null && current.bridge != null) {
            current.bridge.stop();
        }
        RUNTIME = null;
        SERVER = null;
    }

    /** Re-reads the whole file layout from disk (save → apply). */
    public static void reloadSuite() {
        if (SERVER == null) return;
        Path folder = Path.of(".").resolve("textformatter-suite").toAbsolutePath();
        copyDefaultsIfMissing(folder);
        PluginLogger logger = logger();
        PermissionChecker permissions = TextFormatterSuiteMod::hasPermission;
        MESSAGES = MessagesConfig.load(folder, BUILT_IN_MESSAGES);
        TranslationService translation = new TranslationService(TranslatorsConfig.load(folder));
        SuiteHost reloaded = SuiteHost.bootstrap(folder, permissions, translation,
            new FabricPlaceholderResolver(), logger);
        UserLanguageStore languages = LANGUAGE_STORE;
        if (languages == null) {
            languages = new YamlUserLanguageStore(folder);
            LANGUAGE_STORE = languages;
        }
        FabricActorDirectory dirs = new FabricActorDirectory(SERVER, languages);
        FabricChatDelivery delivery = new FabricChatDelivery(SERVER);
        MessageDispatcher dispatcher =
            new MessageDispatcher(reloaded, dirs, delivery, permissions, logger);
        DiscordBridge previous = RUNTIME == null ? null : RUNTIME.bridge;
        if (previous != null) {
            previous.stop();
        }
        DiscordBridge bridge = DiscordBridge.create(folder, dispatcher, logger);
        RUNTIME = new Runtime(reloaded, dispatcher, dirs, languages, bridge);
        if (bridge != null) {
            bridge.start();
        }
    }

    private static boolean hasPermission(Actor actor, String permission) {
        if (permission == null) return true;
        ServerPlayerEntity player = actor == null ? null : actor.handle();
        return player != null && player.hasPermissionLevel(2);
    }

    /**
     * First-boot experience: copies the bundled {@code defaults/} tree
     * into the data folder. Existing user files are never overwritten.
     */
    private static void copyDefaultsIfMissing(Path folder) {
        try (var stream = TextFormatterSuiteMod.class.getResourceAsStream("/defaults/config.yml")) {
            if (stream == null) return;
        } catch (IOException ignored) { return; }
        copyResource(folder, "defaults/config.yml", folder.resolve("config.yml"));
        for (String name : List.of("chat.global")) {
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
            LOGGER.warn("reset falló: " + exception.getMessage());
            return false;
        }
    }

    private static void copyResource(Path folder, String resourcePath, Path target) {
        if (Files.exists(target)) return;
        try (var in = TextFormatterSuiteMod.class.getResourceAsStream("/" + resourcePath)) {
            if (in == null) return;
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
            LOGGER.info("default creado: " + folder.relativize(target));
        } catch (IOException exception) {
            LOGGER.warn("no se pudo crear " + target + ": " + exception.getMessage());
        }
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) { LOGGER.info(format(m, a)); }
            @Override public void warn(String m, Object... a) { LOGGER.warn(format(m, a)); }
            @Override public void error(String m, Object... a) { LOGGER.error(format(m, a)); }
            @Override public void error(String m, Throwable t) { LOGGER.error(m + " :: " + t); }
            @Override public void debug(String m, Object... a) { if (isDebug()) LOGGER.info("[debug] " + format(m, a)); }
        };
    }

    private static boolean isDebug() {
        return Boolean.getBoolean("textformattersuite.debug");
    }

    private static String format(String message, Object... args) {
        return MessagesConfig.substitute(message, args);
    }

    // -- dispatch helpers ---------------------------------------------------

    private static void dispatch(Runtime current, MessageType type, Actor sender,
                                 me.majhrs16.suite.api.message.Direction direction, String channelPath, String text) {
        dispatch(current, type, sender, direction, channelPath, text, true);
    }

    private static void dispatch(Runtime current, MessageType type, Actor sender,
                                 me.majhrs16.suite.api.message.Direction direction, String channelPath, String text,
                                 boolean translate) {
        Message message = Message.builder()
            .type(type)
            .sender(sender)
            .direction(direction.channel(me.majhrs16.suite.api.message.Channel.CHAT))
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
            .direction(me.majhrs16.suite.api.message.Direction.others().channel(me.majhrs16.suite.api.message.Channel.CHAT))
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
            .direction(me.majhrs16.suite.api.message.Direction.all())
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
}

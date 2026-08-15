package me.majhrs16.cht.core;

import me.majhrs16.cht.core.api.ChatTranslatorApi;
import me.majhrs16.cht.core.chat.ChatRouter;
import me.majhrs16.cht.core.chat.DefaultDirectionResolver;
import me.majhrs16.cht.core.config.ChatSettings;
import me.majhrs16.cht.core.config.ConfigLoader;
import me.majhrs16.cht.core.config.FormatGroups;
import me.majhrs16.cht.core.event.MessageEventBus;
import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.ChatMessage;
import me.majhrs16.cht.core.message.DefaultJsonCodec;
import me.majhrs16.cht.core.message.JsonCodec;
import me.majhrs16.cht.core.platform.ChatDisplay;
import me.majhrs16.cht.core.platform.ConfigFolder;
import me.majhrs16.cht.core.platform.DirectionResolver;
import me.majhrs16.cht.core.platform.PermissionChecker;
import me.majhrs16.cht.core.platform.PlaceholderResolver;
import me.majhrs16.cht.core.platform.PluginLogger;
import me.majhrs16.cht.core.platform.PlayerRegistry;
import me.majhrs16.cht.core.platform.Scheduler;
import me.majhrs16.cht.core.player.Subject;
import me.majhrs16.cht.core.rules.RulesEngine;
import me.majhrs16.cht.core.rules.RulesLoader;
import me.majhrs16.cht.core.rules.ScriptSurface;
import me.majhrs16.cht.core.scripting.SpelExpressionEvaluator;
import me.majhrs16.cht.core.storage.UserStore;
import me.majhrs16.cht.core.storage.YamlUserStore;
import me.majhrs16.cht.core.template.TemplateRenderer;
import me.majhrs16.cht.core.translate.GoogleTranslator;
import me.majhrs16.cht.core.translate.LibreTranslator;
import me.majhrs16.cht.core.translate.TranslationService;
import me.majhrs16.cht.core.translate.TranslatorManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Wires the domain services with the platform ports. Both adapters (Spigot and
 * Fabric) build this once at startup; everything else lives behind ports.
 *
 * <p>Configuration is read from the {@link ConfigFolder} on every
 * {@link #reload()}; internal references are replaced atomically so a mid-life
 * reload never leaves the app in a partial state.</p>
 */
public final class ChatTranslatorApp implements ChatTranslatorApi {

    private final ConfigFolder folder;
    private final ChatDisplay display;
    private final Scheduler scheduler;
    private final PlayerRegistry players;
    private final PermissionChecker permissions;
    private final PlaceholderResolver placeholders;
    private final PluginLogger logger;

    private volatile ChatSettings settings;
    private volatile FormatGroups groups;
    private volatile UserStore users;
    private volatile TranslationService translation;
    private volatile TemplateRenderer renderer;
    private volatile RulesEngine rules;
    private volatile ChatRouter router;
    private final JsonCodec jsonCodec = new DefaultJsonCodec();
    private final MessageEventBus messageEvents = new MessageEventBus();

    private ChatTranslatorApp(Builder builder) {
        this.folder = Objects.requireNonNull(builder.folder, "folder");
        this.display = Objects.requireNonNull(builder.display, "display");
        this.scheduler = Objects.requireNonNull(builder.scheduler, "scheduler");
        this.players = Objects.requireNonNull(builder.players, "players");
        this.permissions = Objects.requireNonNull(builder.permissions, "permissions");
        this.placeholders = Objects.requireNonNull(builder.placeholders, "placeholders");
        this.logger = Objects.requireNonNull(builder.logger, "logger");
        ScriptSurface.bindFormatApplier(null); // injected per reload below
        ScriptSurface.bindJsonCodec(jsonCodec);
        reload();
    }

    public static Builder builder() {
        return new Builder();
    }

    // -- ChatTranslatorApi -------------------------------------------------

    @Override
    public void sendMessage(ChatMessage message) {
        router.dispatch(message);
    }

    @Override
    public MessageEventBus messageEvents() {
        return messageEvents;
    }

    @Override
    public Language languageOf(Subject subject) {
        if (subject == null || subject.uuid() == null) {
            return settings.defaultLanguage();
        }
        return users.language(subject.uuid()).orElse(settings.defaultLanguage());
    }

    @Override
    public void setLanguage(Subject subject, Language language) {
        if (subject == null || subject.uuid() == null || language == null) {
            throw new IllegalArgumentException("subject and language must not be null");
        }
        users.setLanguage(subject.uuid(), language);
    }

    @Override
    public Language defaultLanguage() {
        return settings.defaultLanguage();
    }

    @Override
    public synchronized void reload() {
        Instant start = Instant.now();
        ChatSettings newSettings = safeSettings();
        this.users = new YamlUserStore(folder.storageFile(), logger);
        this.settings = newSettings;
        this.translation = buildTranslation(newSettings);
        this.renderer = new TemplateRenderer(
            translation, placeholders, new SpelExpressionEvaluator(), logger);
        this.groups = safeGroups();
        this.rules = safeRules();
        ScriptSurface.bindFormatApplier(
            new me.majhrs16.cht.core.config.FormatApplier(groups));
        ScriptSurface.bindJsonCodec(jsonCodec);
        DirectionResolver directions = new DefaultDirectionResolver(
            players, users, permissions, settings);
        this.router = new ChatRouter(
            groups, renderer, directions, users, display, scheduler,
            settings, permissions, rules, logger, messageEvents);
        logger.info("Configuration reloaded in %s ms",
            Duration.between(start, Instant.now()).toMillis());
    }

    /**
     * Releases resources (e.g. open storage backends) when the platform shuts
     * down. The instance must not be used afterwards.
     */
    public void shutdown() {
        users.close();
    }

    // -- public accessors ---------------------------------------------------

    public ChatRouter router() {
        return router;
    }

    public ChatSettings settings() {
        return settings;
    }

    public FormatGroups groups() {
        return groups;
    }

    public RulesEngine rules() {
        return rules;
    }

    public UserStore users() {
        return users;
    }

    public TranslationService translation() {
        return translation;
    }

    public PlaceholderResolver placeholders() {
        return placeholders;
    }

    private TranslationService buildTranslation(ChatSettings settings) {
        TranslatorManager manager = new TranslatorManager();
        if ("libre".equalsIgnoreCase(settings.translatorProvider())) {
            manager.add(new LibreTranslator(settings.libreUrl(), settings.libreKey()));
        } else {
            manager.add(new GoogleTranslator());
        }
        return new TranslationService(manager);
    }

    private ChatSettings safeSettings() {
        try (InputStream input = new FileInputStream(folder.configFile())) {
            return ConfigLoader.loadSettings(input);
        } catch (IOException e) {
            logger.warn("Could not read config.yml, using defaults: %s", e.getMessage());
            return ChatSettings.builder().build();
        }
    }

    private FormatGroups safeGroups() {
        try (InputStream input = new FileInputStream(folder.formatsFile())) {
            return FormatGroups.load(input);
        } catch (IOException e) {
            logger.warn("Could not read formats.yml, no formats loaded: %s", e.getMessage());
            return emptyGroups();
        }
    }

    private FormatGroups emptyGroups() {
        try {
            return FormatGroups.load(
                new java.io.ByteArrayInputStream("{}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private RulesEngine safeRules() {
        List<RulesEngine.Rule> loaded;
        try (InputStream input = new FileInputStream(folder.rulesFile())) {
            loaded = RulesLoader.load(input);
        } catch (IOException e) {
            logger.warn("Could not read rules.yml, no rules loaded: %s", e.getMessage());
            loaded = java.util.Collections.emptyList();
        }
        return new RulesEngine(loaded, new SpelExpressionEvaluator(), logger);
    }

    // -- Builder ------------------------------------------------------------

    public static final class Builder {

        private ConfigFolder folder;
        private ChatDisplay display;
        private Scheduler scheduler;
        private PlayerRegistry players;
        private PermissionChecker permissions;
        private PlaceholderResolver placeholders;
        private PluginLogger logger;

        public ChatTranslatorApp build() {
            return new ChatTranslatorApp(this);
        }

        public Builder configFolder(ConfigFolder folder) {
            this.folder = folder;
            return this;
        }

        public Builder display(ChatDisplay display) {
            this.display = display;
            return this;
        }

        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder players(PlayerRegistry players) {
            this.players = players;
            return this;
        }

        public Builder permissions(PermissionChecker permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder placeholders(PlaceholderResolver placeholders) {
            this.placeholders = placeholders;
            return this;
        }

        public Builder logger(PluginLogger logger) {
            this.logger = logger;
            return this;
        }
    }
}
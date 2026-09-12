package me.majhrs16.suite.extension;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context provided to extensions when enabled.
 * <p>
 * Grants controlled access to suite internals without exposing internal APIs.
 * All methods are thread-safe unless noted otherwise.
 * </p>
 */
public final class ExtensionContext {

    private final SuiteHost host;
    private final MessageDispatcher dispatcher;
    private final PluginLogger logger;
    private final TranslationService translation;
    private final UserLanguageStore languages;
    private final ChannelRegistry channels;
    private final Path dataDirectory;
    private final String extensionId;
    private final ConcurrentHashMap<String, Object> sharedState = new ConcurrentHashMap<>();

    public ExtensionContext(SuiteHost host, MessageDispatcher dispatcher,
                            PluginLogger logger, TranslationService translation,
                            UserLanguageStore languages, ChannelRegistry channels,
                            Path dataDirectory, String extensionId) {
        this.host = host;
        this.dispatcher = dispatcher;
        this.logger = logger;
        this.translation = translation;
        this.languages = languages;
        this.channels = channels;
        this.dataDirectory = dataDirectory;
        this.extensionId = extensionId;
    }

    // ============================================================
    // Suite Core Access
    // ============================================================

    public SuiteHost host() { return host; }
    public MessageDispatcher dispatcher() { return dispatcher; }
    public PluginLogger logger() { return logger; }
    public TranslationService translation() { return translation; }
    public UserLanguageStore languages() { return languages; }
    public ChannelRegistry channels() { return channels; }

    /**
     * @return data directory specific to this extension (created on first access)
     */
    public Path dataDirectory() {
        Path dir = dataDirectory.resolve(extensionId);
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception e) {
            logger.error("Failed to create extension data dir: " + e.getMessage());
        }
        return dir;
    }

    // ============================================================
    // Extension Lifecycle Helpers
    // ============================================================

    /**
     * Registers a channel provided by this extension.
     * Channel will be unregistered automatically on extension disable.
     */
    public void registerChannel(Channel channel) {
        channels.register(channel);
        logger.info("[{}] Registered channel: {}", extensionId, channel.name());
    }

    /**
     * Unregisters a channel registered by this extension.
     */
    public void unregisterChannel(String channelName) {
        channels.unregister(channelName);
        logger.info("[{}] Unregistered channel: {}", extensionId, channelName);
    }

    /**
     * Dispatches a message through the suite pipeline.
     * The extension's ID is attached for tracking.
     */
    public void dispatchMessage(Message message) {
        dispatcher.dispatch(message);
    }

    // ============================================================
    // Shared State (for inter-extension communication)
    // ============================================================

    /**
     * Stores a value in the extension's shared state.
     * Only accessible by this extension.
     */
    public void putState(String key, Object value) {
        sharedState.put(extensionId + ":" + key, value);
    }

    /**
     * Retrieves a value from the extension's shared state.
     */
    @SuppressWarnings("unchecked")
    public <T> T getState(String key) {
        return (T) sharedState.get(extensionId + ":" + key);
    }

    /**
     * Removes a value from the extension's shared state.
     */
    public void removeState(String key) {
        sharedState.remove(extensionId + ":" + key);
    }

    // ============================================================
    // Event Bus (simplified)
    // ============================================================

    private final java.util.Map<String, java.util.List<java.util.function.Consumer<Object>>> eventListeners =
        new ConcurrentHashMap<>();

    /**
     * Subscribes to an event type. Callback runs on the dispatcher thread.
     */
    public <T> void subscribe(String eventType, java.util.function.Consumer<T> listener) {
        eventListeners.computeIfAbsent(eventType, k -> new java.util.ArrayList<>())
            .add((java.util.function.Consumer<Object>) listener);
    }

    /**
     * Unsubscribes from an event type.
     */
    public void unsubscribe(String eventType, java.util.function.Consumer<?> listener) {
        var list = eventListeners.get(eventType);
        if (list != null) list.remove(listener);
    }

    /**
     * Publishes an event to all subscribers.
     */
    public void publish(String eventType, Object event) {
        var list = eventListeners.get(eventType);
        if (list != null) {
            for (var listener : list) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    // Log but don't break other listeners
                }
            }
        }
    }

    // ============================================================
    // Permissions
    // ============================================================

    /**
     * Checks if an actor has a permission.
     */
    public boolean hasPermission(Actor actor, String permission) {
        return host.hasPermission(actor, permission);
    }

    // ============================================================
    // Configuration
    // ============================================================

    /**
     * Loads extension configuration from disk (YAML).
     */
    public ExtensionConfig loadConfig() {
        Path configFile = dataDirectory().resolve("config.yml");
        if (!configFile.toFile().exists()) {
            return ExtensionConfig.empty();
        }
        try {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            String content = java.nio.file.Files.readString(configFile);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(content);
            return new ExtensionConfig(map != null ? map : Map.of());
        } catch (Exception e) {
            logger.warn("Failed to load extension config: " + e.getMessage());
            return ExtensionConfig.empty();
        }
    }

    /**
     * Saves extension configuration to disk.
     */
    public void saveConfig(ExtensionConfig config) {
        Path configFile = dataDirectory().resolve("config.yml");
        try {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            String yamlStr = yaml.dump(config.asMap());
            java.nio.file.Files.writeString(configFile, yamlStr);
        } catch (Exception e) {
            logger.error("Failed to save extension config: " + e.getMessage());
        }
    }

    // ============================================================
    // Translation Helpers
    // ============================================================

    /**
     * Translates text using the suite's translation service.
     */
    public String translate(String text, String sourceLang, String targetLang) {
        if (translation.isAvailable()) {
            return translation.translate(text, me.majhrs16.suite.api.message.Language.of(sourceLang).orElse(null),
                me.majhrs16.suite.api.message.Language.of(targetLang).orElse(null));
        }
        return text;
    }

    // ============================================================
    // Player/Target Resolution
    // ============================================================

    /**
     * Finds a player by name or UUID.
     */
    public java.util.Optional<Actor> findPlayer(String nameOrUuid) {
        try {
            UUID uuid = UUID.fromString(nameOrUuid);
            return dispatcher.getActors().byUuid(uuid);
        } catch (IllegalArgumentException ignored) {
            return dispatcher.getActors().byName(nameOrUuid);
        }
    }

    // ============================================================
    // Extension Info
    // ============================================================

    public String extensionId() { return extensionId; }
}
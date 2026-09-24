package me.majhrs16.suite.presets;

import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.host.config.HostConfig;

import java.util.List;
import java.util.Map;

/**
 * A preset is a complete configuration snapshot that can be applied
 * to instantly set up the suite for a specific use case.
 */
public final class Preset {

    private final String id;
    private final String name;
    private final String description;
    private final String author;
    private final String version;
    private final String minSuiteVersion;
    private final List<String> tags;
    private final PresetConfig config;
    private final List<PresetChannel> channels;
    private final List<PresetRule> rules;
    private final List<PresetTranslator> translators;
    private final List<PresetSync> sync;

    public Preset(String id, String name, String description, String author,
                  String version, String minSuiteVersion, List<String> tags,
                  PresetConfig config, List<PresetChannel> channels,
                  List<PresetRule> rules, List<PresetTranslator> translators,
                  List<PresetSync> sync) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.author = author;
        this.version = version;
        this.minSuiteVersion = minSuiteVersion;
        this.tags = tags;
        this.config = config;
        this.channels = channels;
        this.rules = rules;
        this.translators = translators;
        this.sync = sync;
    }

    // Getters
    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public String author() { return author; }
    public String version() { return version; }
    public String minSuiteVersion() { return minSuiteVersion; }
    public List<String> tags() { return tags; }
    public PresetConfig config() { return config; }
    public List<PresetChannel> channels() { return channels; }
    public List<PresetRule> rules() { return rules; }
    public List<PresetTranslator> translators() { return translators; }
    public List<PresetSync> sync() { return sync; }

    /**
     * Applies this preset to the given registry and configuration.
     */
    public void apply(ChannelRegistry registry, HostConfig hostConfig,
                      List<Rule> ruleList, List<Channel> channelList) {
        // Apply config
        // Apply channels
        // Apply rules
        // Apply translators
        // Apply sync
    }

    // ============================================================
    // Nested data classes
    // ============================================================

    public static final class PresetConfig {
        private final boolean quickLook;
        private final String language;
        private final boolean parallel;
        private final boolean soundEnabled;
        private final String claimMode;

        public PresetConfig(boolean quickLook, String language, boolean parallel,
                           boolean soundEnabled, String claimMode) {
            this.quickLook = quickLook;
            this.language = language;
            this.parallel = parallel;
            this.soundEnabled = soundEnabled;
            this.claimMode = claimMode;
        }

        public boolean quickLook() { return quickLook; }
        public String language() { return language; }
        public boolean parallel() { return parallel; }
        public boolean soundEnabled() { return soundEnabled; }
        public String claimMode() { return claimMode; }
    }

    public static final class PresetChannel {
        private final String name;
        private final String type; // CHAT or EVENT
        private final String permission;
        private final String sendPermission;
        private final String receivePermission;
        private final boolean showSender;
        private final int rateLimitPerSecond;
        private final String langSource;
        private final String langTarget;
        private final List<String> messages;
        private final List<String> tooltips;
        private final List<PresetSound> sounds;

        public PresetChannel(String name, String type, String permission,
                            String sendPermission, String receivePermission,
                            boolean showSender, int rateLimitPerSecond,
                            String langSource, String langTarget,
                            List<String> messages, List<String> tooltips,
                            List<PresetSound> sounds) {
            this.name = name;
            this.type = type;
            this.permission = permission;
            this.sendPermission = sendPermission;
            this.receivePermission = receivePermission;
            this.showSender = showSender;
            this.rateLimitPerSecond = rateLimitPerSecond;
            this.langSource = langSource;
            this.langTarget = langTarget;
            this.messages = messages;
            this.tooltips = tooltips;
            this.sounds = sounds;
        }

        // Getters
        public String name() { return name; }
        public String type() { return type; }
        public String permission() { return permission; }
        public String sendPermission() { return sendPermission; }
        public String receivePermission() { return receivePermission; }
        public boolean showSender() { return showSender; }
        public int rateLimitPerSecond() { return rateLimitPerSecond; }
        public String langSource() { return langSource; }
        public String langTarget() { return langTarget; }
        public List<String> messages() { return messages; }
        public List<String> tooltips() { return tooltips; }
        public List<PresetSound> sounds() { return sounds; }
    }

    public static final class PresetSound {
        private final String name;
        private final float volume;
        private final float pitch;

        public PresetSound(String name, float volume, float pitch) {
            this.name = name;
            this.volume = volume;
            this.pitch = pitch;
        }

        public String name() { return name; }
        public float volume() { return volume; }
        public float pitch() { return pitch; }
    }

    public static final class PresetRule {
        private final String id;
        private final int priority;
        private final String condition;
        private final String action;
        private final String targetChannel;

        public PresetRule(String id, int priority, String condition,
                         String action, String targetChannel) {
            this.id = id;
            this.priority = priority;
            this.condition = condition;
            this.action = action;
            this.targetChannel = targetChannel;
        }

        // Getters
        public String id() { return id; }
        public int priority() { return priority; }
        public String condition() { return condition; }
        public String action() { return action; }
        public String targetChannel() { return targetChannel; }
    }

    public static final class PresetTranslator {
        private final String provider; // google or libre
        private final boolean active;
        private final String baseUrl;
        private final String apiKey;
        private final int maxConcurrent;

        public PresetTranslator(String provider, boolean active, String baseUrl,
                               String apiKey, int maxConcurrent) {
            this.provider = provider;
            this.active = active;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.maxConcurrent = maxConcurrent;
        }

        // Getters
        public String provider() { return provider; }
        public boolean active() { return active; }
        public String baseUrl() { return baseUrl; }
        public String apiKey() { return apiKey; }
        public int maxConcurrent() { return maxConcurrent; }
    }

    public static final class PresetSync {
        private final String type; // discord, telegram, http, tcp-udp, velocity
        private final boolean enabled;
        private final Map<String, String> config;

        public PresetSync(String type, boolean enabled, Map<String, String> config) {
            this.type = type;
            this.enabled = enabled;
            this.config = config;
        }

        public String type() { return type; }
        public boolean enabled() { return enabled; }
        public Map<String, String> config() { return config; }
    }
}
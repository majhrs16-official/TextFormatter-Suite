package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the suite's file layout (config.yml + channels/*.yml) into the
 * resolved {@link HostConfig} and {@link ChannelRegistry}.
 *
 * <p>Every field is optional; defaults mirror the suite defaults. Unknown
 * keys are ignored so older configs degrade gracefully.</p>
 */
public final class ConfigLoader {

    private static final Yaml YAML = new Yaml();

    private ConfigLoader() {
    }

    /** Loads {@code config.yml} from {@code dir}; missing file → defaults. */
    public static HostConfig loadConfig(Path dir) {
        Path file = dir.resolve("config.yml");
        if (!Files.exists(file)) {
            return HostConfig.defaults();
        }
        try {
            Object root = YAML.load(Files.readString(file));
            if (!(root instanceof Map)) {
                return HostConfig.defaults();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;

            boolean quickLook = bool(map.get(ConfigPath.QUICK_LOOK.key()), true);
            boolean parallel = bool(nested(map, ConfigPath.IFLOW, ConfigPath.IFLOW_ENGINE, ConfigPath.IFLOW_ENGINE_PARALLEL), false);
            boolean sound = bool(nested(map, ConfigPath.SONIDO, ConfigPath.SONIDO_ENABLED), true);
            Language defaultLang = Language.of(str(nested(map, ConfigPath.GENERAL, ConfigPath.GENERAL_LANGUAGE), "en"))
                .orElse(Language.EN);
            HostConfig.ClaimMode claimMode = claimMode(nested(map, ConfigPath.CHAT, ConfigPath.CHAT_CLAIM_MODE));

            return new HostConfig(quickLook, defaultLang, parallel, sound, claimMode);
        } catch (IOException | RuntimeException e) {
            // YAML malformado: degradar a defaults en vez de tumbar el bootstrap.
            return HostConfig.defaults();
        }
    }

    /**
     * Loads every {@code channels/*.yml} into a {@link ChannelRegistry}.
     * Missing directory or files yield an empty registry (resolver falls back
     * to a synthetic {@code chat} channel).
     */
    public static ChannelRegistry loadChannels(Path dir) {
        Path channelsDir = dir.resolve("channels");
        if (!Files.isDirectory(channelsDir)) {
            return ChannelRegistry.builder().build();
        }
        ChannelRegistry.Builder builder = ChannelRegistry.builder();
        try (var stream = Files.list(channelsDir)) {
            stream.filter(p -> p.toString().endsWith(".yml"))
                .forEach(p -> readChannel(p).ifPresent(builder::register));
        } catch (IOException | RuntimeException e) {
            return ChannelRegistry.builder().build();
        }
        return builder.build();
    }

    private static java.util.Optional<Channel> readChannel(Path file) {
        try {
            Object root = YAML.load(Files.readString(file));
            if (!(root instanceof Map)) {
                return java.util.Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;
            String name = str(map.get(ConfigPath.CHANNEL_NAME.key()), fileName(file));
            if (name == null || name.isBlank()) {
                return java.util.Optional.empty();
            }

            Channel.Builder builder = Channel.builder(name)
                .permission(str(map.get(ConfigPath.CHANNEL_PERMISSION.key()), null))
                .sendPermission(str(map.get(ConfigPath.CHANNEL_SEND_PERMISSION.key()), null))
                .receivePermission(str(map.get(ConfigPath.CHANNEL_RECEIVE_PERMISSION.key()), null))
                .messages(readFormats(map.get(ConfigPath.CHANNEL_MESSAGES.key())))
                .tooltips(readFormats(map.get(ConfigPath.CHANNEL_TOOLTIPS.key())))
                .showSender(bool(map.get(ConfigPath.CHANNEL_SHOW_SENDER.key()), true))
                .rateLimitPerSecond(intOf(map.get(ConfigPath.CHANNEL_RATE_LIMIT.key()), 0));

            Language source = Language.of(str(map.get(ConfigPath.CHANNEL_LANG_SOURCE.key()), "auto")).orElse(Language.AUTO);
            Language target = Language.of(str(map.get(ConfigPath.CHANNEL_LANG_TARGET.key()), "auto")).orElse(Language.AUTO);
            builder.langSource(source).langTarget(target);

            builder.sounds(readSounds(map.get(ConfigPath.CHANNEL_SOUNDS.key())));
            return java.util.Optional.of(builder.build());
        } catch (IOException | RuntimeException e) {
            // Archivo de canal corrupto: se omite sin tumbar los demás.
            return java.util.Optional.empty();
        }
    }

    // -- helpers ----------------------------------------------------------

    private static Formats readFormats(Object raw) {
        if (!(raw instanceof List)) {
            return Formats.empty();
        }
        @SuppressWarnings("unchecked")
        List<String> templates = ((List<Object>) raw).stream()
            .map(String::valueOf)
            .toList();
        return new Formats(new String[0], templates.toArray(new String[0]));
    }

    private static List<SoundSpec> readSounds(Object raw) {
        if (!(raw instanceof List)) {
            return List.of();
        }
        List<SoundSpec> sounds = new java.util.ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) raw;
        for (Object entry : entries) {
            if (entry instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) entry;
                String name = str(m.get(ConfigPath.SOUND_NAME.key()), null);
                if (name != null) {
                    sounds.add(new SoundSpec(name,
                        floatOf(m.get(ConfigPath.SOUND_VOLUME.key()), 1.0f),
                        floatOf(m.get(ConfigPath.SOUND_PITCH.key()), 1.0f)));
                }
            }
        }
        return sounds;
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> map, ConfigPath... path) {
        Object current = map;
        for (ConfigPath key : path) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(key.key());
        }
        return current;
    }

    private static HostConfig.ClaimMode claimMode(Object raw) {
        if (!(raw instanceof String value)) {
            return HostConfig.ClaimMode.CANCEL_EVENT;
        }
        try {
            return HostConfig.ClaimMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            return HostConfig.ClaimMode.CANCEL_EVENT;
        }
    }

    private static String fileName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static float floatOf(Object value, float fallback) {
        return value instanceof Number n ? n.floatValue() : fallback;
    }

    /**
     * Centralized config keys — single source of truth for YAML paths.
     */
    public enum ConfigPath {
        QUICK_LOOK("quick-look"),
        IFLOW("iflow"),
        IFLOW_ENGINE("engine"),
        IFLOW_ENGINE_PARALLEL("parallel"),
        SONIDO("sonido"),
        SONIDO_ENABLED("enabled"),
        GENERAL("general"),
        GENERAL_LANGUAGE("language"),
        CHAT("chat"),
        CHAT_CLAIM_MODE("claim-mode"),
        CHANNEL_NAME("name"),
        CHANNEL_PERMISSION("permission"),
        CHANNEL_SEND_PERMISSION("send-permission"),
        CHANNEL_RECEIVE_PERMISSION("receive-permission"),
        CHANNEL_MESSAGES("messages"),
        CHANNEL_TOOLTIPS("tooltips"),
        CHANNEL_SHOW_SENDER("show-sender"),
        CHANNEL_RATE_LIMIT("rate-limit-per-second"),
        CHANNEL_LANG_SOURCE("lang-source"),
        CHANNEL_LANG_TARGET("lang-target"),
        CHANNEL_SOUNDS("sounds"),
        SOUND_NAME("name"),
        SOUND_VOLUME("volume"),
        SOUND_PITCH("pitch");

        private final String key;

        ConfigPath(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.PluginLogger;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Reads the suite-level {@code config.yml} into the resolved {@link HostConfig}.
 * <p>
 * La definición de canales es propiedad de {@code textformatter} y se carga con
 * {@code me.majhrs16.suite.textformatter.ChannelConfig}.
 *
 * <p>Every field is optional; defaults mirror the suite defaults. Unknown
 * keys are ignored so older configs degrade gracefully. Errors are logged
 * at ERROR level so administrators can see them; a degraded config is
 * returned but config validity state is tracked.</p>
 */
public final class ConfigLoader {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private ConfigLoader() {
    }

    /**
     * Result of config loading including validity state.
     */
    public static final class LoadResult<T> {
        private final T config;
        private final boolean valid;
        private final List<String> errors;

        private LoadResult(T config, boolean valid, List<String> errors) {
            this.config = config;
            this.valid = valid;
            this.errors = errors;
        }

        public static <T> LoadResult<T> success(T config) {
            return new LoadResult<>(config, true, List.of());
        }

        public static <T> LoadResult<T> degraded(T config, List<String> errors) {
            return new LoadResult<>(config, false, errors);
        }

        public T config() { return config; }
        public boolean isValid() { return valid; }
        public List<String> errors() { return errors; }
    }

    /** Loads {@code config/suite/config.yml} (legacy {@code config.yml}); missing → defaults. */
    public static LoadResult<HostConfig> loadConfig(Path dir, PluginLogger logger) {
        List<String> errors = new ArrayList<>();
        Path file = HostPaths.suiteConfig(dir);
        if (!Files.exists(file)) {
            String msg = "config.yml not found at " + file + "; using defaults";
            if (logger != null) logger.error(msg);
            errors.add(msg);
            return LoadResult.degraded(HostConfig.defaults(), errors);
        }
        try {
            Object root = YAML.load(Files.readString(file));
            if (!(root instanceof Map)) {
                String msg = "config.yml root is not a map; using defaults";
                if (logger != null) logger.error(msg);
                errors.add(msg);
                return LoadResult.degraded(HostConfig.defaults(), errors);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;

            boolean quickLook = bool(map.get(ConfigPath.QUICK_LOOK.key()), true);
            boolean parallel = bool(nested(map, ConfigPath.IFLOW, ConfigPath.IFLOW_ENGINE, ConfigPath.IFLOW_ENGINE_PARALLEL), false);
            boolean sound = bool(nested(map, ConfigPath.SONIDO, ConfigPath.SONIDO_ENABLED), true);
            Language defaultLang = Language.of(str(nested(map, ConfigPath.GENERAL, ConfigPath.GENERAL_LANGUAGE), "en"))
                .orElse(Language.EN);
            HostConfig.ClaimMode claimMode = claimMode(nested(map, ConfigPath.CHAT, ConfigPath.CHAT_CLAIM_MODE));
            boolean logChatToConsole = bool(nested(map, ConfigPath.CHAT, ConfigPath.CHAT_LOG_TO_CONSOLE), true);
            List<HostConfig.Repository> repositories = parseRepositories(map, logger);
            List<String> moduleAllowlist = parseModuleAllowlist(map);

            return LoadResult.success(new HostConfig(quickLook, defaultLang, parallel, sound, claimMode, logChatToConsole, repositories, moduleAllowlist));
        } catch (IOException | RuntimeException e) {
            String msg = "Failed to load config.yml: " + e.getMessage();
            if (logger != null) logger.error(msg, e);
            errors.add(msg);
            return LoadResult.degraded(HostConfig.defaults(), errors);
        }
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

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
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
        CHAT_LOG_TO_CONSOLE("log-to-console"),
        REPOSITORIES("repositories"),
        REPOSITORY_NAME("name"),
        REPOSITORY_URL("url"),
        REPOSITORY_TYPE("type"),
        REPOSITORY_ENABLED("enabled"),
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
        CHANNEL_TYPE("type"),
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

    private static List<HostConfig.Repository> parseRepositories(Map<String, Object> map, PluginLogger logger) {
        List<HostConfig.Repository> repositories = new ArrayList<>();
        Object reposObj = map.get(ConfigPath.REPOSITORIES.key());
        if (!(reposObj instanceof List)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object> reposList = (List<Object>) reposObj;
        for (Object repoObj : reposList) {
            if (!(repoObj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> repoMap = (Map<String, Object>) repoObj;
            String name = str(repoMap.get(ConfigPath.REPOSITORY_NAME.key()), "");
            String url = str(repoMap.get(ConfigPath.REPOSITORY_URL.key()), "");
            String type = str(repoMap.get(ConfigPath.REPOSITORY_TYPE.key()), "github");
            boolean enabled = bool(repoMap.get(ConfigPath.REPOSITORY_ENABLED.key()), true);
            if (!name.isBlank() && !url.isBlank()) {
                repositories.add(new HostConfig.Repository(name, url, type, enabled));
            } else if (logger != null) {
                logger.warn("Skipping invalid repository entry: missing name or url");
            }
        }
        return repositories;
    }

    private static List<String> parseModuleAllowlist(Map<String, Object> map) {
        Object allowlistObj = map.get("module-allowlist");
        if (allowlistObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) allowlistObj;
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String) {
                    result.add((String) item);
                }
            }
            return result;
        }
        return List.of();
    }
}
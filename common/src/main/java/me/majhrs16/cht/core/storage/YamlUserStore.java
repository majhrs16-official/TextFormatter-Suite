package me.majhrs16.cht.core.storage;

import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.platform.PluginLogger;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Flat-file {@link UserStore} backed by a single YAML document.
 *
 * <p>Thread safe: every mutation is serialized and persisted immediately. Not
 * recommended for large player bases, but ideal as the default and always
 * available backend.</p>
 */
public final class YamlUserStore implements UserStore {

    private final File file;
    private final Yaml yaml;
    private final Map<String, Map<String, Object>> entries;
    private final PluginLogger logger;

    public YamlUserStore(File file) {
        this(file, null);
    }

    public YamlUserStore(File file, PluginLogger logger) {
        this.file = file;
        this.logger = logger;
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        this.yaml = new Yaml(options);
        this.entries = new HashMap<>();
        load();
    }

    @Override
    public Optional<Language> language(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        synchronized (entries) {
            Map<String, Object> entry = entries.get(uuid.toString());
            if (entry == null) {
                return Optional.empty();
            }
            Optional<Language> language = Language.of(String.valueOf(entry.get("lang")));
            return language.filter(l -> l != Language.AUTO);
        }
    }

    @Override
    public void setLanguage(UUID uuid, Language language) {
        if (uuid == null) {
            return;
        }
        synchronized (entries) {
            entry(uuid).put("lang", language.code());
            save();
        }
    }

    @Override
    public Optional<String> discordLink(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        synchronized (entries) {
            Map<String, Object> entry = entries.get(uuid.toString());
            if (entry == null) {
                return Optional.empty();
            }
            String discord = (String) entry.get("discord");
            return discord == null ? Optional.empty() : Optional.of(discord);
        }
    }

    @Override
    public void linkDiscord(UUID uuid, String discordId) {
        if (uuid == null) {
            return;
        }
        synchronized (entries) {
            entry(uuid).put("discord", discordId);
            save();
        }
    }

    @Override
    public void unlinkDiscord(UUID uuid) {
        if (uuid == null) {
            return;
        }
        synchronized (entries) {
            Map<String, Object> entry = entries.get(uuid.toString());
            if (entry != null) {
                entry.remove("discord");
                save();
            }
        }
    }

    @Override
    public Optional<UUID> playerBoundToDiscord(String discordId) {
        synchronized (entries) {
            for (Map.Entry<String, Map<String, Object>> entry : entries.entrySet()) {
                if (discordId.equals(entry.getValue().get("discord"))) {
                    try {
                        return Optional.of(UUID.fromString(entry.getKey()));
                    } catch (IllegalArgumentException e) {
                        if (logger != null) {
                            logger.warn("Corrupted uuid '%s' in %s, skipping entry",
                                entry.getKey(), file.getPath());
                        }
                    }
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public String type() {
        return "yaml";
    }

    @Override
    public void close() {
        // nothing to close for a flat file
    }

    private Map<String, Object> entry(UUID uuid) {
        return entries.computeIfAbsent(uuid.toString(), ignored -> new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!file.exists()) {
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            Object parsed = yaml.load(reader);
            if (parsed instanceof Map) {
                for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) parsed).entrySet()) {
                    if (entry.getValue() instanceof Map) {
                        entries.put(String.valueOf(entry.getKey()),
                            (Map<String, Object>) entry.getValue());
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }

    private void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                yaml.dump(entries, writer);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file, e);
        }
    }
}
package me.majhrs16.suite.presets;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.message.SoundSpec;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Real file-backed {@link PresetTarget}.
 *
 * <p>Persists a preset into the canonical configuration layout of the suite:
 * suite settings, textformatter channels, chattranslator backends and sync
 * transports. Existing YAML keys that the preset does not touch are preserved;
 * a preset is a patch, not a full replacement of unrelated configuration.</p>
 *
 * <p>The target does not reload the host. The caller that applies a preset must
 * reload the affected modules after {@link PresetManager#apply(String,
 * PresetTarget)} returns, so runtime state and files agree.</p>
 */
public final class FilePresetTarget implements PresetTarget {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));
    private static final Yaml WRITER = writer();

    private final Path suiteDir;

    /**
     * @param suiteDir adapter data directory (the same directory that contains
     *                 {@code config/})
     */
    public FilePresetTarget(Path suiteDir) {
        this.suiteDir = Objects.requireNonNull(suiteDir, "suiteDir")
            .toAbsolutePath()
            .normalize();
    }

    @Override
    public void applyConfig(Preset.PresetConfig config) {
        Objects.requireNonNull(config, "config");
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("quick-look", config.quickLook());
        putNested(patch, List.of("general", "language"), config.language());
        putNested(patch, List.of("iflow", "engine", "parallel"), config.parallel());
        putNested(patch, List.of("sonido", "enabled"), config.soundEnabled());
        putNested(patch, List.of("chat", "claim-mode"), config.claimMode());
        mergeWrite(suiteDir.resolve("config/suite/config.yml"), patch);
    }

    @Override
    public void applyChannel(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("name", channel.name());
        patch.put("type", channel.type().name());
        putIfPresent(patch, "permission", channel.permission());
        putIfPresent(patch, "send-permission", channel.sendPermission());
        putIfPresent(patch, "receive-permission", channel.receivePermission());
        patch.put("show-sender", channel.showSender());
        patch.put("rate-limit-per-second", channel.rateLimitPerSecond());
        patch.put("lang-source", channel.langSource().code());
        patch.put("lang-target", channel.langTarget().code());
        patch.put("messages", templates(channel.messages()));
        patch.put("tooltips", templates(channel.tooltips()));

        List<Map<String, Object>> sounds = new ArrayList<>();
        for (SoundSpec sound : channel.sounds()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", sound.name());
            entry.put("volume", sound.volume());
            entry.put("pitch", sound.pitch());
            sounds.add(entry);
        }
        patch.put("sounds", sounds);

        Path channelsDir = suiteDir.resolve("config/textformatter/channels");
        mergeWrite(channelsDir.resolve(safeFileName(channel.name()) + ".yml"), patch);
    }

    @Override
    public void applyTranslator(Preset.PresetTranslator translator) {
        Objects.requireNonNull(translator, "translator");
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("provider", translator.provider());
        patch.put("active", translator.active());
        putIfPresent(patch, "base-url", translator.baseUrl());
        putIfPresent(patch, "api-key", translator.apiKey());
        if (translator.maxConcurrent() > 0) {
            patch.put("max-concurrent", translator.maxConcurrent());
        }

        Path translatorsDir = suiteDir.resolve("config/chattranslator/translators");
        mergeWrite(translatorsDir.resolve(safeFileName(translator.provider()) + ".yml"), patch);
    }

    @Override
    public void applySync(Preset.PresetSync sync) {
        Objects.requireNonNull(sync, "sync");
        String type = sync.type().toLowerCase(Locale.ROOT);
        switch (type) {
            case "discord" -> applyDiscord(sync);
            case "telegram" -> applyTelegram(sync);
            case "http" -> applyHttp(sync);
            case "tcp-udp" -> applySocket(sync);
            case "velocity" -> applyVelocity(sync);
            default -> throw new PresetApplyException(
                "unsupported preset sync type: " + sync.type());
        }
    }

    private void applyDiscord(Preset.PresetSync sync) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("enabled", sync.enabled());
        copyIfPresent(patch, sync, "token", "token");
        copyIfPresent(patch, sync, "channel", "channel");
        copyIfPresent(patch, sync, "format", "format");
        copyIfPresent(patch, sync, "transport", "transport");
        copyIfPresent(patch, sync, "intents", "intents");
        mergeWrite(suiteDir.resolve("config/sync-discord/config.yml"), patch);
    }

    private void applyTelegram(Preset.PresetSync sync) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("enabled", sync.enabled());
        copyIfPresent(patch, sync, "token", "token");
        copyIfPresent(patch, sync, "chat-id", "chatId");
        mergeWrite(suiteDir.resolve("config/sync-telegram/config.yml"), patch);
    }

    private void applyHttp(Preset.PresetSync sync) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("enabled", sync.enabled());
        copyIfPresent(patch, sync, "webhook-url", "outboundUrl");
        copyIfPresent(patch, sync, "inbound-port", "inboundPort");
        copyIfPresent(patch, sync, "path", "inboundPath");
        copyIfPresent(patch, sync, "bind", "bind");
        copyIfPresent(patch, sync, "auth-token", "authToken");
        copyIfPresent(patch, sync, "hmac-secret", "hmacSecret");
        mergeWrite(suiteDir.resolve("config/sync-http/config.yml"), patch);
    }

    private void applySocket(Preset.PresetSync sync) {
        String protocol = sync.config().getOrDefault("protocol", "TCP").trim().toLowerCase(Locale.ROOT);
        String file = "udp".equals(protocol) ? "udp.yml" : "tcp.yml";
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("enabled", sync.enabled());
        copyIfPresent(patch, sync, "host", "remoteHost");
        copyIfPresent(patch, sync, "outbound-port", "remotePort");
        copyIfPresent(patch, sync, "inbound-port", "localPort");
        mergeWrite(suiteDir.resolve("config/sync-socket").resolve(file), patch);
    }

    private void applyVelocity(Preset.PresetSync sync) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("enabled", sync.enabled());
        copyIfPresent(patch, sync, "secret", "secret");
        copyIfPresent(patch, sync, "mapping", "mapping");
        copyIfPresent(patch, sync, "channel", "channel");
        copyIfPresent(patch, sync, "subchannel", "subchannel");
        String servers = sync.config().get("servers");
        if (servers != null) {
            patch.put("servers", splitList(servers));
        }
        mergeWrite(suiteDir.resolve("config/sync-velocity-core/config.yml"), patch);
    }

    private static List<String> splitList(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "[]".equals(value)) {
            return List.of();
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    private static void copyIfPresent(Map<String, Object> target,
                                      Preset.PresetSync sync,
                                      String sourceKey,
                                      String targetKey) {
        String value = sync.config().get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    private static List<String> templates(Formats formats) {
        List<String> values = formats.formatList();
        if (values.stream().anyMatch(value -> value != null && !value.isBlank())) {
            return List.copyOf(values);
        }
        return List.copyOf(formats.textList());
    }

    private static void putNested(Map<String, Object> root,
                                  List<String> path,
                                  Object value) {
        Map<String, Object> current = root;
        for (int i = 0; i < path.size() - 1; i++) {
            String key = path.get(i);
            Object next = current.get(key);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                current.put(key, next);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) next;
            current = nested;
        }
        current.put(path.get(path.size() - 1), value);
    }

    private static void putIfPresent(Map<String, Object> target,
                                     String key,
                                     String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String safeFileName(String value) {
        String safe = value == null ? "" : value.trim()
            .replace('/', '_')
            .replace('\\', '_');
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            throw new PresetApplyException("invalid preset file name: " + value);
        }
        return safe;
    }

    private static void mergeWrite(Path file, Map<String, Object> patch) {
        try {
            Map<String, Object> root = readMap(file);
            merge(root, patch);
            writeMap(file, root);
        } catch (IOException e) {
            throw new PresetApplyException("could not persist preset to " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        Object loaded = YAML.load(Files.readString(file, StandardCharsets.UTF_8));
        if (loaded == null) {
            return new LinkedHashMap<>();
        }
        if (!(loaded instanceof Map<?, ?>)) {
            throw new IOException("YAML root is not a map: " + file);
        }
        return new LinkedHashMap<>((Map<String, Object>) loaded);
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            Object current = target.get(entry.getKey());
            Object incoming = entry.getValue();
            if (current instanceof Map<?, ?> && incoming instanceof Map<?, ?>) {
                merge((Map<String, Object>) current, (Map<String, Object>) incoming);
            } else {
                target.put(entry.getKey(), incoming);
            }
        }
    }

    private static void writeMap(Path file, Map<String, Object> value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, WRITER.dump(value), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Yaml writer() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options);
    }
}

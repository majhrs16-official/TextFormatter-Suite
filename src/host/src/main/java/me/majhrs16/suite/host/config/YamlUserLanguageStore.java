package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.spi.UserLanguageStore;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YAML-backed {@link UserLanguageStore} ({@code storage.yml}: uuid → value).
 *
 * <p>I/O policy: the map is cached in RAM and the file is only re-parsed when
 * its {@code lastModified} timestamp changes ("Guardar → Aplicar" hot reload
 * without watchers or per-event parsing). Saves update the cache immediately
 * and write through atomically (temp file + move).</p>
 */
public final class YamlUserLanguageStore implements UserLanguageStore {

    private static final Yaml YAML = new Yaml(dumperOptions());

    private final Path file;
    private final ConcurrentHashMap<UUID, String> cache = new ConcurrentHashMap<>();
    private volatile long lastLoadedStamp = Long.MIN_VALUE;

    public YamlUserLanguageStore(Path dataFolder) {
        this.file = dataFolder.resolve("storage.yml");
        loadIfChanged();
    }

    @Override
    public Optional<String> languageOf(UUID uuid) {
        loadIfChanged();
        return Optional.ofNullable(cache.get(uuid)).filter(v -> !v.isBlank());
    }

    @Override
    public void save(UUID uuid, String value) {
        if (value == null || value.isBlank()) {
            cache.remove(uuid);
        } else {
            cache.put(uuid, value.trim());
        }
        writeThrough();
    }

    @Override
    public synchronized void flush() {
        writeThrough();
    }

    /** Re-reads the file only when its timestamp moved (hot external edits). */
    private synchronized void loadIfChanged() {
        try {
            if (!Files.exists(file)) {
                return;
            }
            long stamp = Files.getLastModifiedTime(file).toMillis();
            if (stamp == lastLoadedStamp) {
                return;
            }
            lastLoadedStamp = stamp;
            Object root = YAML.load(Files.readString(file));
            if (!(root instanceof Map)) {
                return;
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) root).entrySet()) {
                try {
                    cache.put(UUID.fromString(String.valueOf(entry.getKey())),
                        String.valueOf(entry.getValue()));
                } catch (IllegalArgumentException ignored) {
                    // clave no-uuid (edición manual): se ignora esa entrada.
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // archivo corrupto/ilegible: se conserva el caché vigente.
        }
    }

    private synchronized void writeThrough() {
        try {
            Files.createDirectories(file.getParent());
            Map<String, String> ordered = new LinkedHashMap<>();
            cache.keySet().stream().sorted().forEach(id -> ordered.put(id.toString(), cache.get(id)));
            Path temp = Files.createTempFile(file.getParent(), "storage", ".tmp");
            Files.writeString(temp, YAML.dump(ordered));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            lastLoadedStamp = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            // el caché en RAM sigue siendo la fuente vigente hasta el próximo flush.
        }
    }

    private static DumperOptions dumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return options;
    }
}

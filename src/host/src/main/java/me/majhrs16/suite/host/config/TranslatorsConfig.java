package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslatorProvider;
import me.majhrs16.suite.api.spi.TranslationException;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Loads {@code translators/*.yml} (schema v2.2) into a ready
 * {@link TranslatorManager}.
 *
 * <p>Only {@code active: true} providers are registered. Providers are
 * discovered via {@link ServiceLoader} (SPI) rather than hardcoded,
 * enabling Clean Architecture compliance where host depends on abstractions.</p>
 *
 * <p>Order: providers are sorted by rank (google=0, libre=1, then by name).</p>
 */
public final class TranslatorsConfig {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));
    private static final Map<String, TranslatorProvider> PROVIDER_REGISTRY = discoverProviders();

    private TranslatorsConfig() {
    }

    /** Builds the manager from {@code dir/translators/*.yml}. Never null. */
    public static TranslatorManager load(Path dir) {
        Path translatorsDir = dir.resolve("translators");
        List<Provider> providers = readProviders(translatorsDir);
        TranslatorManager manager = new TranslatorManager();

        for (Provider p : providers) {
            if (p.active() && p.settings() != null) {
                manager.add(p.settings());
            }
        }
        return manager;
    }

    /**
     * Discovers all TranslatorProvider implementations via ServiceLoader.
     * Returns a map of provider name -> provider instance.
     */
    private static Map<String, TranslatorProvider> discoverProviders() {
        Map<String, TranslatorProvider> registry = new java.util.HashMap<>();
        ServiceLoader<TranslatorProvider> loader = ServiceLoader.load(TranslatorProvider.class);
        for (TranslatorProvider provider : loader) {
            registry.put(provider.name(), provider);
        }
        return registry;
    }

    private static List<Provider> readProviders(Path translatorsDir) {
        if (!Files.isDirectory(translatorsDir)) {
            return List.of();
        }
        List<Provider> found = new ArrayList<>();
        try (var stream = Files.list(translatorsDir)) {
            stream.filter(p -> p.toString().endsWith(".yml"))
                .forEach(p -> readProvider(p).ifPresent(found::add));
        } catch (IOException ignored) {
            return List.of();
        }
        return orderCanonical(found);
    }

    private static java.util.Optional<Provider> readProvider(Path file) {
        try {
            Object root = YAML.load(Files.readString(file));
            if (!(root instanceof Map)) {
                return java.util.Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;

            String kind = str(map.get("provider"), fileName(file));
            boolean active = bool(map.get("active"), false);

            TranslatorProvider spiProvider = PROVIDER_REGISTRY.get(kind);
            if (spiProvider == null) {
                // Unknown provider type - skip with warning
                return java.util.Optional.empty();
            }

            // Build config map from YAML (all non-provider/active fields)
            Map<String, Object> config = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                if (!"provider".equals(key) && !"active".equals(key)) {
                    config.put(key, entry.getValue());
                }
            }

            Translator settings = null;
            if (active && spiProvider.validateConfig(config)) {
                try {
                    settings = spiProvider.create(config);
                } catch (TranslationException e) {
                    // Provider creation failed - log and skip
                }
            }

            return java.util.Optional.of(new Provider(kind, active, settings));
        } catch (IOException | RuntimeException e) {
            // YAML malformado o provider desconocido: se ignora ese archivo,
            // el resto de translators/*.yml sigue cargando.
            return java.util.Optional.empty();
        }
    }

    /** Deterministic registration: google first, libre second, rest by name. */
    private static List<Provider> orderCanonical(List<Provider> found) {
        return found.stream()
            .sorted((a, b) -> Integer.compare(rank(a.kind()), rank(b.kind())))
            .toList();
    }

    private static int rank(String kind) {
        return "google".equals(kind) ? 0 : "libre".equals(kind) ? 1 : 2;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    private static String fileName(Path file) {
        String n = file.getFileName().toString();
        return n.substring(0, n.lastIndexOf('.'));
    }

    private record Provider(String kind, boolean active, Translator settings) {}
}

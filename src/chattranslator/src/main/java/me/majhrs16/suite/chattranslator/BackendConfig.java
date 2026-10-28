package me.majhrs16.suite.chattranslator;

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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Carga la configuración de backends de traducción desde {@code chattranslator/translators/*.yml}.
 * <p>
 * Descubre proveedores vía {@link ServiceLoader} (SPI) y los registra en un {@link TranslatorManager}.
 * Este componente reemplaza a {@code TranslatorsConfig} del host, moviendo la responsabilidad
 * al módulo dueño (chattranslator).
 * </p>
 */
public final class BackendConfig {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    /**
     * Legacy host path only. Module activators pass their providers explicitly,
     * so they never trigger a ServiceLoader scan against the host classpath.
     */
    private static final class ServiceLoaderRegistry {
        private static final Map<String, TranslatorProvider> INSTANCE = discoverProviders();
    }

    private BackendConfig() {
    }

    /**
     * Construye el manager desde {@code dir/config/chattranslator/translators/*.yml}.
     * <p>
     * Se mantienen las rutas heredadas ({@code chattranslator/translators/} y
     * {@code translators/}) durante la migración al layout de {@code storage.md}.
     * </p>
     *
     * @param dir directorio base del suite (ej. plugin data folder)
     * @return TranslatorManager poblado con proveedores activos
     */
    public static TranslatorManager load(Path dir) {
        return load(resolveTranslatorsDir(dir), ServiceLoaderRegistry.INSTANCE);
    }

    /**
     * Loads the configured backends using providers supplied by the runtime
     * instead of the static {@link ServiceLoader} registry.
     *
     * @param dir       suite data directory
     * @param providers translator providers visible to the calling module
     * @return populated manager
     */
    public static TranslatorManager load(Path dir, Collection<TranslatorProvider> providers) {
        return load(resolveTranslatorsDir(dir), registryOf(providers));
    }

    /**
     * Loads {@code <configDir>/translators/*.yml} for a module-scoped
     * configuration directory.
     *
     * @param configDir module configuration directory
     * @param providers translator providers visible to the calling module
     * @return populated manager
     */
    public static TranslatorManager loadFromConfigDir(Path configDir,
                                                      Collection<TranslatorProvider> providers) {
        return load(configDir.resolve("translators"), registryOf(providers));
    }

    private static TranslatorManager load(Path translatorsDir,
                                          Map<String, TranslatorProvider> registry) {
        List<Provider> providers = readProviders(translatorsDir, registry);
        TranslatorManager manager = new TranslatorManager();

        for (Provider p : providers) {
            if (p.active() && p.settings() != null) {
                manager.add(p.settings());
            }
        }
        return manager;
    }

    private static Map<String, TranslatorProvider> registryOf(
            Collection<TranslatorProvider> providers) {
        Map<String, TranslatorProvider> registry = new java.util.HashMap<>();
        for (TranslatorProvider provider : providers) {
            registry.putIfAbsent(provider.name(), provider);
        }
        return registry;
    }

    /** @return el primer directorio de backends existente, o la ruta canónica. */
    public static Path resolveTranslatorsDir(Path dir) {
        Path canonical = dir.resolve("config").resolve("chattranslator").resolve("translators");
        if (Files.isDirectory(canonical)) {
            return canonical;
        }
        Path intermediate = dir.resolve("chattranslator").resolve("translators");
        if (Files.isDirectory(intermediate)) {
            return intermediate;
        }
        Path legacy = dir.resolve("translators");
        if (Files.isDirectory(legacy)) {
            return legacy;
        }
        return canonical;
    }

    /**
     * Descubre todos los TranslatorProvider implementations via ServiceLoader.
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

    private static List<Provider> readProviders(Path translatorsDir,
                                                Map<String, TranslatorProvider> registry) {
        if (!Files.isDirectory(translatorsDir)) {
            return List.of();
        }
        List<Provider> found = new ArrayList<>();
        try (var stream = Files.list(translatorsDir)) {
            stream.filter(p -> p.toString().endsWith(".yml"))
                .forEach(p -> readProvider(p, registry).ifPresent(found::add));
        } catch (IOException ignored) {
            return List.of();
        }
        return orderCanonical(found);
    }

    private static java.util.Optional<Provider> readProvider(
            Path file, Map<String, TranslatorProvider> registry) {
        try {
            Object root = YAML.load(Files.readString(file));
            if (!(root instanceof Map)) {
                return java.util.Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;

            String kind = str(map.get("provider"), fileName(file));
            boolean active = bool(map.get("active"), false);

            TranslatorProvider spiProvider = registry.get(kind);
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
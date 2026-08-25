package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.gtranslate.GTranslate;
import me.majhrs16.suite.gtranslate.HttpTransport;
import me.majhrs16.suite.ltranslate.LTranslate;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code translators/*.yml} (schema v2.2) into a ready
 * {@link TranslatorManager}.
 *
 * <p>Only {@code active: true} providers are registered, google before
 * libre regardless of directory order. Missing directory yields an empty
 * manager (the manager's FALLBACK "none" translator keeps the pipeline
 * functional without translation); a malformed or unknown provider file is
 * skipped, never fatal for the rest.</p>
 *
 * <p>{@code pool.max-concurrent} belongs to the schema but no engine exposes
 * a concurrency knob yet, so it is not consumed (F7+).</p>
 */
public final class TranslatorsConfig {

    private static final Yaml YAML = new Yaml();

    private TranslatorsConfig() {
    }

    /** Builds the manager from {@code dir/translators/*.yml}. Never null. */
    public static TranslatorManager load(Path dir) {
        Path translatorsDir = dir.resolve("translators");
        List<Provider> providers = readProviders(translatorsDir);
        TranslatorManager manager = new TranslatorManager();

        providers.stream()
            .filter(Provider::active)
            .filter(p -> p.settings() != null)
            .forEach(p -> manager.add(p.settings()));
        return manager;
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
            String baseUrl = str(map.get("base-url"), "");
            String apiKey = str(map.get("api-key"), "");

            Translator settings = switch (kind) {
                case "google" -> new GTranslate(new HttpTransport());
                case "libre" -> baseUrl.isBlank()
                    ? null
                    : (apiKey.isBlank()
                        ? new LTranslate(baseUrl, new me.majhrs16.suite.ltranslate.HttpTransport())
                        : new LTranslate(baseUrl, apiKey, new me.majhrs16.suite.ltranslate.HttpTransport()));
                default -> null;
            };
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
        return switch (kind) {
            case "google" -> 0;
            case "libre" -> 1;
            default -> 2;
        };
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

    private record Provider(String kind, boolean active, Translator settings) {
    }
}

package me.majhrs16.suite.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.majhrs16.suite.api.command.CommandRoot;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.config.HostPaths;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads the command-root configuration from {@code config/suite/commands.yml}.
 *
 * <p>The supported schema is deliberately small: {@code root} and
 * {@code aliases}. The legacy {@code base-name} key is migrated to a
 * single-segment root with a warning; it is removed in F8. The old
 * {@code actions}/{@code commands} topology is not loaded because command
 * behavior is code in packages, not a YAML interpreter.</p>
 */
final class CommandRootConfig {

    private static final String FILE = "commands.yml";
    private static final String LEGACY_BASE_NAME = "base-name";
    private static final String REMOVAL_PHASE = "F8";

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private CommandRootConfig() {
    }

    static ConfigLoader.LoadResult<CommandRoot> load(Path dataDir, PluginLogger logger) {
        Path file = HostPaths.suiteCommands(dataDir);
        if (!Files.exists(file)) {
            if (logger != null) {
                logger.debug("commands.yml not found at " + file + "; using /suite");
            }
            return ConfigLoader.LoadResult.success(CommandRoot.suite());
        }

        List<String> errors = new ArrayList<>();
        try {
            Object raw = YAML.load(Files.readString(file));
            if (raw == null) {
                return ConfigLoader.LoadResult.success(CommandRoot.suite());
            }
            if (!(raw instanceof Map<?, ?> map)) {
                errors.add("commands.yml root is not a map");
                return degraded(errors);
            }

            List<String> segments = null;
            boolean legacyRoot = false;
            Object rootValue = map.get("root");
            if (rootValue != null) {
                segments = segments(rootValue, "root", errors);
            }
            Object legacy = map.get(LEGACY_BASE_NAME);
            if (segments == null && legacy != null) {
                segments = segments(legacy, LEGACY_BASE_NAME, errors);
                legacyRoot = segments != null;
                if (segments != null && logger != null) {
                    logger.warn("commands.yml uses legacy '" + LEGACY_BASE_NAME
                            + "'; migrate to 'root'. Compatibility is removed in "
                            + REMOVAL_PHASE + ".");
                }
            } else if (legacy != null && logger != null) {
                logger.warn("commands.yml ignores legacy '" + LEGACY_BASE_NAME
                        + "' because 'root' is present.");
            }
            if (segments == null) {
                if (errors.isEmpty()) {
                    segments = List.of("suite");
                } else {
                    return degraded(errors);
                }
            }

            List<List<String>> aliases = aliases(
                    map.get("aliases"), segments, errors, legacyRoot);
            if (!errors.isEmpty()) {
                return degraded(errors);
            }
            return ConfigLoader.LoadResult.success(new CommandRoot(segments, aliases));
        } catch (IOException | RuntimeException failure) {
            errors.add("cannot read commands.yml: " + failure.getMessage());
            if (logger != null) {
                logger.error("Cannot read " + FILE + " at " + file, failure);
            }
            return degraded(errors);
        }
    }

    private static ConfigLoader.LoadResult<CommandRoot> degraded(List<String> errors) {
        return ConfigLoader.LoadResult.degraded(CommandRoot.suite(), List.copyOf(errors));
    }

    private static List<String> segments(Object raw, String label, List<String> errors) {
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                errors.add("commands.yml " + label + " must not be blank");
                return null;
            }
            return List.of(trimmed.split("\\s+"));
        }
        if (!(raw instanceof List<?> values)) {
            errors.add("commands.yml " + label + " must be a string or a list of strings");
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) {
                errors.add("commands.yml " + label + " contains a non-string segment");
                return null;
            }
            segments.add(text.trim());
        }
        if (segments.isEmpty()) {
            errors.add("commands.yml " + label + " must not be empty");
            return null;
        }
        return List.copyOf(segments);
    }

    private static List<List<String>> aliases(Object raw, List<String> root,
                                              List<String> errors,
                                              boolean legacyMigration) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values)) {
            errors.add("commands.yml aliases must be a list");
            return List.of();
        }
        List<List<String>> aliases = new ArrayList<>();
        for (Object value : values) {
            List<String> alias = segments(value, "alias", errors);
            if (alias == null) {
                continue;
            }
            if (legacyMigration && alias.equals(root)) {
                // Old files often repeated the base name in aliases.
                continue;
            }
            aliases.add(alias);
        }
        return List.copyOf(aliases);
    }
}

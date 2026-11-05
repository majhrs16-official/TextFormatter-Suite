package me.majhrs16.suite.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.majhrs16.suite.api.command.CommandRoot;
import me.majhrs16.suite.api.config.ConfigVersion;
import me.majhrs16.suite.api.config.ConfigVersionException;
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
 * {@code aliases}. Unknown keys are ignored; in particular, the retired
 * {@code base-name} key does not set the root. The old
 * {@code actions}/{@code commands} topology is not loaded because command
 * behavior is code in packages, not a YAML interpreter.</p>
 */
final class CommandRootConfig {

    private static final String FILE = "commands.yml";

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
            ConfigVersion.require(map, file);

            List<String> segments = null;
            Object rootValue = map.get("root");
            if (rootValue != null) {
                segments = segments(rootValue, "root", errors);
            }
            if (segments == null) {
                if (errors.isEmpty()) {
                    segments = List.of("suite");
                } else {
                    return degraded(errors);
                }
            }

            List<List<String>> aliases = aliases(map.get("aliases"), segments, errors);
            if (!errors.isEmpty()) {
                return degraded(errors);
            }
            return ConfigLoader.LoadResult.success(new CommandRoot(segments, aliases));
        } catch (ConfigVersionException failure) {
            errors.add(failure.getMessage());
            return degraded(errors);
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
                                              List<String> errors) {
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
            if (alias.equals(root)) {
                errors.add("commands.yml alias must not repeat the root");
                continue;
            }
            aliases.add(alias);
        }
        return List.copyOf(aliases);
    }
}

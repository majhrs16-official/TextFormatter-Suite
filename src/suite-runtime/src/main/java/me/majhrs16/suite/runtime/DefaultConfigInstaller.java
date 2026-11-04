package me.majhrs16.suite.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.config.HostPaths;

/**
 * First-boot installer for the bundled configuration defaults.
 *
 * <p>Host settings are written under {@code config/suite/}; module-owned
 * settings are written under {@code manager/config/<module>/}, the same root
 * that {@code ModuleHost} passes to each activator. Existing canonical files
 * are never overwritten.</p>
 *
 * <p>Legacy files from the previous flat and {@code config/<module>} layouts
 * are migrated to the manager-scoped path so activators read the same content
 * they used before. The legacy candidates are scheduled for removal in F9,
 * once installations created before the manager-scoped layout are no longer
 * supported.</p>
 */
public final class DefaultConfigInstaller {

    private static final List<String> CHANNELS =
            List.of("chat.global", "join", "quit", "death", "advancement");

    private DefaultConfigInstaller() {
    }

    /**
     * Installs every bundled default that is missing from its canonical path.
     *
     * @param dataFolder adapter data folder
     * @param resources  resource opener, usually {@code Class::getResourceAsStream}
     * @param logger     diagnostic sink
     */
    public static void install(Path dataFolder, Function<String, InputStream> resources,
                               PluginLogger logger) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(logger, "logger");

        install(resources, logger, "defaults/config.yml",
                HostPaths.canonicalSuiteConfig(dataFolder),
                dataFolder.resolve("config.yml"));
        install(resources, logger, "defaults/commands.yml",
                HostPaths.canonicalSuiteCommands(dataFolder),
                dataFolder.resolve("commands.yml"));
        install(resources, logger, "defaults/messages.yml",
                HostPaths.canonicalSuiteMessages(dataFolder),
                dataFolder.resolve("messages.yml"));

        for (String name : CHANNELS) {
            install(resources, logger, "defaults/channels/" + name + ".yml",
                    HostPaths.moduleConfigDir(dataFolder, "textformatter")
                            .resolve("channels/" + name + ".yml"),
                    dataFolder.resolve("config/textformatter/channels/" + name + ".yml"),
                    dataFolder.resolve("channels/" + name + ".yml"));
        }
        install(resources, logger, "defaults/translators/google.yml",
                HostPaths.moduleConfigDir(dataFolder, "chattranslator")
                        .resolve("translators/google.yml"),
                dataFolder.resolve("config/chattranslator/translators/google.yml"),
                dataFolder.resolve("translators/google.yml"));
        install(resources, logger, "defaults/sync/discord.yml",
                HostPaths.moduleConfigDir(dataFolder, "sync-discord").resolve("config.yml"),
                dataFolder.resolve("config/sync-discord/config.yml"),
                dataFolder.resolve("sync/discord.yml"));
        install(resources, logger, "defaults/sync/websocket.yml",
                HostPaths.moduleConfigDir(dataFolder, "sync-websocket").resolve("config.yml"),
                dataFolder.resolve("config/sync-websocket/config.yml"),
                dataFolder.resolve("sync/websocket.yml"));
        install(resources, logger, "defaults/inworld.yml",
                HostPaths.moduleConfigDir(dataFolder, "inworld").resolve("config.yml"),
                dataFolder.resolve("config/inworld/config.yml"),
                dataFolder.resolve("inworld.yml"));
        install(resources, logger, "defaults/rules.yml",
                HostPaths.moduleConfigDir(dataFolder, "iflow-firewall").resolve("rules.yml"),
                dataFolder.resolve("config/iflow-firewall/rules.yml"),
                dataFolder.resolve("rules.yml"));
    }

    private static void install(Function<String, InputStream> resources, PluginLogger logger,
                                String resourcePath, Path canonical, Path... legacy) {
        if (Files.exists(canonical)) {
            return;
        }
        for (Path candidate : legacy) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                copyFile(logger, candidate, canonical);
                return;
            }
        }
        try (InputStream in = resources.apply("/" + resourcePath)) {
            if (in == null) {
                return;
            }
            Files.createDirectories(canonical.getParent());
            Files.copy(in, canonical);
            logger.info("Default file created: " + canonical);
        } catch (IOException exception) {
            logger.warn("Cannot create " + canonical + ": " + exception.getMessage());
        }
    }

    private static void copyFile(PluginLogger logger, Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            logger.info("Config migrated: " + source + " -> " + target);
        } catch (IOException exception) {
            logger.warn("Cannot migrate " + source + " to " + target + ": "
                    + exception.getMessage());
        }
    }
}

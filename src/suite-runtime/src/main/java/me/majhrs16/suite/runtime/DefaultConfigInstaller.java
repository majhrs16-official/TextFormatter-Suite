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
 * settings are written under {@code config/<module>/}, the same root that
 * {@code ModuleHost} passes to each activator. Existing canonical files are
 * never overwritten.</p>
 *
 * <p>Las rutas {@code config/manager/<module>/} y {@code manager/config/<module>/}
 * son incorrectas: no se leen, no se aceptan como fallback ni como layouts
 * legacy y el instalador no las migra ni las elimina. Las rutas planas antiguas
 * ({@code channels/...}, {@code translators/...}, {@code sync/...}, etc.) sí se
 * conservan como legacy durante la migración.</p>
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
            Path canonical = HostPaths.moduleConfigDir(dataFolder, "textformatter")
                    .resolve("channels/" + name + ".yml");
            install(resources, logger, "defaults/channels/" + name + ".yml", canonical,
                    dataFolder.resolve("channels/" + name + ".yml"));
        }
        Path googleTranslator = HostPaths.moduleConfigDir(dataFolder, "chattranslator")
                .resolve("translators/google.yml");
        install(resources, logger, "defaults/translators/google.yml", googleTranslator,
                dataFolder.resolve("translators/google.yml"));

        Path discord = HostPaths.moduleConfigDir(dataFolder, "sync-discord").resolve("config.yml");
        install(resources, logger, "defaults/sync/discord.yml", discord,
                dataFolder.resolve("sync/discord.yml"));

        Path websocket = HostPaths.moduleConfigDir(dataFolder, "sync-websocket").resolve("config.yml");
        install(resources, logger, "defaults/sync/websocket.yml", websocket,
                dataFolder.resolve("sync/websocket.yml"));

        Path inworld = HostPaths.moduleConfigDir(dataFolder, "inworld").resolve("config.yml");
        install(resources, logger, "defaults/inworld.yml", inworld,
                dataFolder.resolve("inworld.yml"));

        Path firewallRules = HostPaths.moduleConfigDir(dataFolder, "iflow-firewall")
                .resolve("rules.yml");
        install(resources, logger, "defaults/rules.yml", firewallRules,
                dataFolder.resolve("rules.yml"));

        Path iflowHub = HostPaths.moduleConfigDir(dataFolder, "iflow").resolve("config.yml");
        install(resources, logger, "defaults/iflow.yml", iflowHub,
                dataFolder.resolve("iflow.yml"));
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

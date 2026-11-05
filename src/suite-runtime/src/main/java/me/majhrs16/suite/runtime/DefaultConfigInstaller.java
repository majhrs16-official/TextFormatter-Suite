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
 * <p>Las rutas {@code config/manager/<module>/}, {@code manager/config/<module>/}
 * y las rutas planas en la raíz del data folder no se leen, no se migran y no
 * se eliminan. El instalador solo escribe defaults canónicos ausentes.</p>
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
                HostPaths.canonicalSuiteConfig(dataFolder));
        install(resources, logger, "defaults/commands.yml",
                HostPaths.canonicalSuiteCommands(dataFolder));
        install(resources, logger, "defaults/messages.yml",
                HostPaths.canonicalSuiteMessages(dataFolder));

        for (String name : CHANNELS) {
            Path canonical = HostPaths.moduleConfigDir(dataFolder, "textformatter")
                    .resolve("channels/" + name + ".yml");
            install(resources, logger, "defaults/channels/" + name + ".yml", canonical);
        }
        Path googleTranslator = HostPaths.moduleConfigDir(dataFolder, "chattranslator")
                .resolve("translators/google.yml");
        install(resources, logger, "defaults/translators/google.yml", googleTranslator);

        Path discord = HostPaths.moduleConfigDir(dataFolder, "sync-discord").resolve("config.yml");
        install(resources, logger, "defaults/sync/discord.yml", discord);

        Path websocket = HostPaths.moduleConfigDir(dataFolder, "sync-websocket").resolve("config.yml");
        install(resources, logger, "defaults/sync/websocket.yml", websocket);

        Path inworld = HostPaths.moduleConfigDir(dataFolder, "inworld").resolve("config.yml");
        install(resources, logger, "defaults/inworld.yml", inworld);

        Path firewallRules = HostPaths.moduleConfigDir(dataFolder, "iflow-firewall")
                .resolve("rules.yml");
        install(resources, logger, "defaults/rules.yml", firewallRules);

        Path iflowHub = HostPaths.moduleConfigDir(dataFolder, "iflow").resolve("config.yml");
        install(resources, logger, "defaults/iflow.yml", iflowHub);
    }

    private static void install(Function<String, InputStream> resources, PluginLogger logger,
                                String resourcePath, Path canonical) {
        if (Files.exists(canonical)) {
            return;
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

}

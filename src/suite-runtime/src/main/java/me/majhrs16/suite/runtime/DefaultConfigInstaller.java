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
 * son incorrectas: no se aceptan como fallback ni como layouts legacy. El
 * instalador las detecta únicamente para repararlas moviendo el archivo a la
 * ruta canónica. Las rutas planas antiguas ({@code channels/...},
 * {@code translators/...}, {@code sync/...}, etc.) sí se conservan como
 * legacy durante la migración.</p>
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
            repair(logger, canonical,
                    dataFolder.resolve("config/manager/textformatter/channels/" + name + ".yml"),
                    dataFolder.resolve("manager/config/textformatter/channels/" + name + ".yml"));
            install(resources, logger, "defaults/channels/" + name + ".yml", canonical,
                    dataFolder.resolve("channels/" + name + ".yml"));
        }
        Path googleTranslator = HostPaths.moduleConfigDir(dataFolder, "chattranslator")
                .resolve("translators/google.yml");
        repair(logger, googleTranslator,
                dataFolder.resolve("config/manager/chattranslator/translators/google.yml"),
                dataFolder.resolve("manager/config/chattranslator/translators/google.yml"));
        install(resources, logger, "defaults/translators/google.yml", googleTranslator,
                dataFolder.resolve("translators/google.yml"));

        Path discord = HostPaths.moduleConfigDir(dataFolder, "sync-discord").resolve("config.yml");
        repair(logger, discord,
                dataFolder.resolve("config/manager/sync-discord/config.yml"),
                dataFolder.resolve("manager/config/sync-discord/config.yml"));
        install(resources, logger, "defaults/sync/discord.yml", discord,
                dataFolder.resolve("sync/discord.yml"));

        Path websocket = HostPaths.moduleConfigDir(dataFolder, "sync-websocket").resolve("config.yml");
        repair(logger, websocket,
                dataFolder.resolve("config/manager/sync-websocket/config.yml"),
                dataFolder.resolve("manager/config/sync-websocket/config.yml"));
        install(resources, logger, "defaults/sync/websocket.yml", websocket,
                dataFolder.resolve("sync/websocket.yml"));

        Path inworld = HostPaths.moduleConfigDir(dataFolder, "inworld").resolve("config.yml");
        repair(logger, inworld,
                dataFolder.resolve("config/manager/inworld/config.yml"),
                dataFolder.resolve("manager/config/inworld/config.yml"));
        install(resources, logger, "defaults/inworld.yml", inworld,
                dataFolder.resolve("inworld.yml"));

        Path firewallRules = HostPaths.moduleConfigDir(dataFolder, "iflow-firewall")
                .resolve("rules.yml");
        repair(logger, firewallRules,
                dataFolder.resolve("config/manager/iflow-firewall/rules.yml"),
                dataFolder.resolve("manager/config/iflow-firewall/rules.yml"));
        install(resources, logger, "defaults/rules.yml", firewallRules,
                dataFolder.resolve("rules.yml"));

        Path iflowHub = HostPaths.moduleConfigDir(dataFolder, "iflow").resolve("config.yml");
        repair(logger, iflowHub,
                dataFolder.resolve("config/manager/iflow/config.yml"),
                dataFolder.resolve("manager/config/iflow/config.yml"));
        install(resources, logger, "defaults/iflow.yml", iflowHub,
                dataFolder.resolve("iflow.yml"));
    }

    /**
     * Repara una ruta incorrecta moviendo el archivo a la ruta canónica. No es
     * un fallback: el runtime nunca lee {@code config/manager} ni
     * {@code manager/config}.
     */
    private static void repair(PluginLogger logger, Path canonical, Path... incorrect) {
        if (Files.exists(canonical)) {
            return;
        }
        for (Path source : incorrect) {
            if (source == null || !Files.isRegularFile(source)) {
                continue;
            }
            try {
                Files.createDirectories(canonical.getParent());
                Files.move(source, canonical);
                logger.info("Incorrect config path repaired: " + source + " -> " + canonical);
                return;
            } catch (IOException exception) {
                logger.warn("Cannot repair " + source + " to " + canonical + ": "
                        + exception.getMessage());
            }
        }
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

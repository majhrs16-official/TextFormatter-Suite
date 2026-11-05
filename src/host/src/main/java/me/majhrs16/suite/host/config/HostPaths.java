package me.majhrs16.suite.host.config;

import java.nio.file.Path;

/**
 * Resuelve las rutas de configuración del host dentro del layout de
 * {@code docs/prompts/diseno/storage.md}.
 * <p>
 * La disposición canónica es {@code config/suite/} para la configuración global
 * ({@code config.yml}, {@code commands.yml}, {@code messages.yml}) y
 * {@code config/<module>/} para la configuración de cada módulo. Las rutas
 * planas en la raíz del data folder no se leen ni se migran. Las rutas
 * {@code config/manager/<module>/} y {@code manager/config/<module>/} son
 * incorrectas: no son layouts válidos y tampoco se leen.
 * </p>
 */
public final class HostPaths {

    private HostPaths() {
    }

    /** @return {@code <data>/config} */
    public static Path configRoot(Path dataFolder) {
        return dataFolder.resolve("config");
    }

    /** @return {@code <data>/config/suite} */
    public static Path suiteDir(Path dataFolder) {
        return configRoot(dataFolder).resolve("suite");
    }

    /** @return {@code <data>/config}, root of module-owned settings */
    public static Path moduleConfigRoot(Path dataFolder) {
        return configRoot(dataFolder);
    }

    /** @return {@code <data>/config/<moduleId>} */
    public static Path moduleConfigDir(Path dataFolder, String moduleId) {
        return moduleConfigRoot(dataFolder).resolve(moduleId);
    }

    /** @return ruta canónica de {@code config.yml} (destino de escritura). */
    public static Path canonicalSuiteConfig(Path dataFolder) {
        return suiteDir(dataFolder).resolve("config.yml");
    }

    /** @return ruta canónica de {@code messages.yml} (destino de escritura). */
    public static Path canonicalSuiteMessages(Path dataFolder) {
        return suiteDir(dataFolder).resolve("messages.yml");
    }

    /** @return ruta canónica de {@code commands.yml} (destino de escritura). */
    public static Path canonicalSuiteCommands(Path dataFolder) {
        return suiteDir(dataFolder).resolve("commands.yml");
    }

    /** @return ruta canónica de {@code config.yml}. */
    public static Path suiteConfig(Path dataFolder) {
        return canonicalSuiteConfig(dataFolder);
    }

    /** @return ruta canónica de {@code messages.yml}. */
    public static Path suiteMessages(Path dataFolder) {
        return canonicalSuiteMessages(dataFolder);
    }

    /** @return ruta canónica de {@code commands.yml}. */
    public static Path suiteCommands(Path dataFolder) {
        return canonicalSuiteCommands(dataFolder);
    }
}

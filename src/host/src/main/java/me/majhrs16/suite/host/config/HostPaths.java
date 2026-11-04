package me.majhrs16.suite.host.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resuelve las rutas de configuración del host dentro del layout de
 * {@code docs/prompts/diseno/storage.md}.
 * <p>
 * La disposición canónica es {@code config/suite/} para la configuración global
 * ({@code config.yml}, {@code commands.yml}, {@code messages.yml}) y
 * {@code config/<module>/} para la configuración de cada módulo. Las rutas
 * planas anteriores ({@code config.yml}, {@code commands.yml},
 * {@code messages.yml} en la raíz del data folder) se mantienen como fallback
 * durante la migración para no romper instalaciones existentes. Las rutas
 * {@code config/manager/<module>/} y {@code manager/config/<module>/} son
 * incorrectas: no son layouts válidos ni fallback, y el instalador solo las
 * detecta para repararlas moviéndolas a {@code config/<module>/}.
 * </p>
 * <p>
 * Regla de resolución: se prefiere la ruta canónica si existe; si no, la heredada
 * si existe; si ninguna existe, se devuelve la canónica (destino de escritura).
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

    /** @return {@code config/suite/config.yml} si existe, si no {@code config.yml} si existe, si no la canónica. */
    public static Path suiteConfig(Path dataFolder) {
        return resolve(canonicalSuiteConfig(dataFolder), dataFolder.resolve("config.yml"));
    }

    /** @return {@code config/suite/messages.yml} si existe, si no {@code messages.yml} si existe, si no la canónica. */
    public static Path suiteMessages(Path dataFolder) {
        return resolve(canonicalSuiteMessages(dataFolder), dataFolder.resolve("messages.yml"));
    }

    /** @return {@code config/suite/commands.yml} si existe, si no {@code commands.yml} si existe, si no la canónica. */
    public static Path suiteCommands(Path dataFolder) {
        return resolve(canonicalSuiteCommands(dataFolder), dataFolder.resolve("commands.yml"));
    }

    private static Path resolve(Path canonical, Path legacy) {
        if (Files.exists(canonical)) {
            return canonical;
        }
        if (Files.exists(legacy)) {
            return legacy;
        }
        return canonical;
    }
}

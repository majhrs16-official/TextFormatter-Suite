package me.majhrs16.suite.host.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resuelve las rutas de configuración del host dentro del layout de
 * {@code docs/prompts/diseno/storage.md}.
 * <p>
 * La disposición canónica es {@code config/suite/} para la configuración global
 * ({@code config.yml}, {@code messages.yml}). Las rutas planas anteriores
 * ({@code config.yml}, {@code messages.yml} en la raíz del data folder) se
 * mantienen como fallback durante la migración para no romper instalaciones
 * existentes.
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

    /** @return ruta canónica de {@code config.yml} (destino de escritura). */
    public static Path canonicalSuiteConfig(Path dataFolder) {
        return suiteDir(dataFolder).resolve("config.yml");
    }

    /** @return ruta canónica de {@code messages.yml} (destino de escritura). */
    public static Path canonicalSuiteMessages(Path dataFolder) {
        return suiteDir(dataFolder).resolve("messages.yml");
    }

    /** @return {@code config/suite/config.yml} si existe, si no {@code config.yml} si existe, si no la canónica. */
    public static Path suiteConfig(Path dataFolder) {
        return resolve(canonicalSuiteConfig(dataFolder), dataFolder.resolve("config.yml"));
    }

    /** @return {@code config/suite/messages.yml} si existe, si no {@code messages.yml} si existe, si no la canónica. */
    public static Path suiteMessages(Path dataFolder) {
        return resolve(canonicalSuiteMessages(dataFolder), dataFolder.resolve("messages.yml"));
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

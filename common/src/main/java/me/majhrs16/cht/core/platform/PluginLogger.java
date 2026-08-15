package me.majhrs16.cht.core.platform;

/**
 * Port over the platform logging facility.
 *
 * <p>Implementations route lines to the Bukkit logger, a slf4j logger inside a
 * Fabric mod, or a plain stdout sink in tests. Debug/level filtering is the
 * implementation's responsibility.</p>
 */
public interface PluginLogger {

    void info(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Object... args);

    void error(String message, Throwable throwable);

    void debug(String message, Object... args);
}
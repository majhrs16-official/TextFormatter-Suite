package me.majhrs16.cht.spigot;

import me.majhrs16.cht.core.config.ConfigLoader;
import me.majhrs16.cht.core.platform.PluginLogger;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spigot {@link PluginLogger} delegating to the plugin's java.util.logging
 * logger. The debug flag is read from {@code config.yml} through the shared
 * snakeyaml loader -- never through Bukkit YAML.
 */
final class SpigotLogger implements PluginLogger {

    private final Logger logger;
    private final boolean debug;

    SpigotLogger(JavaPlugin plugin) {
        this.logger = plugin.getLogger();
        this.debug = readDebug(new File(plugin.getDataFolder(), "config.yml"));
    }

    private static boolean readDebug(File file) {
        if (!file.exists()) {
            return false;
        }
        try (InputStream input = new FileInputStream(file)) {
            return ConfigLoader.loadSettings(input).debug();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void info(String message, Object... args) {
        logger.info(String.format(message, args));
    }

    @Override
    public void warn(String message, Object... args) {
        logger.warning(String.format(message, args));
    }

    @Override
    public void error(String message, Object... args) {
        logger.severe(String.format(message, args));
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.log(Level.SEVERE, message, throwable);
    }

    @Override
    public void debug(String message, Object... args) {
        if (debug) {
            logger.fine(String.format(message, args));
        }
    }
}
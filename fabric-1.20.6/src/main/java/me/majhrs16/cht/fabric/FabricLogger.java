package me.majhrs16.cht.fabric;

import me.majhrs16.cht.core.config.ConfigLoader;
import me.majhrs16.cht.core.platform.PluginLogger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Fabric {@link PluginLogger} delegating to slf4j. The debug flag comes from
 * the shared {@code config.yml} (read through the snakeyaml loader), or from
 * the {@code chattranslator.debug} system property.
 */
final class FabricLogger implements PluginLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("ChatTranslator");

    private final boolean debug;

    FabricLogger() {
        this.debug = Boolean.getBoolean("chattranslator.debug")
            || readDebug(new FabricConfigFolder().configFile());
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
        LOGGER.info(String.format(message, args));
    }

    @Override
    public void warn(String message, Object... args) {
        LOGGER.warn(String.format(message, args));
    }

    @Override
    public void error(String message, Object... args) {
        LOGGER.error(String.format(message, args));
    }

    @Override
    public void error(String message, Throwable throwable) {
        LOGGER.error(message, throwable);
    }

    @Override
    public void debug(String message, Object... args) {
        if (debug) {
            LOGGER.debug(String.format(message, args));
        }
    }
}
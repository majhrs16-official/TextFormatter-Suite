package me.majhrs16.suite.spigothost.validator;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.config.HostConfig;

import java.nio.file.Path;

/**
 * Configuration validator for TextFormatter Suite on Spigot.
 * <p>
 * Placeholder implementation for FASE 3 Item 18 - structural config validation.
 * Currently passes all configs without validation.
 */
public final class ConfigValidator {

    private ConfigValidator() {}

    public static void validate(HostConfig config, PluginLogger logger, Path configDir) {
        // TODO: Implement structural validation (FASE 3 Item 18)
        // For now, accept all configurations
    }

    public static final class InvalidConfigException extends Exception {
        public InvalidConfigException(String message) {
            super(message);
        }
    }
}
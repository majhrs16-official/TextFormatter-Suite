package me.majhrs16.suite.spigothost.validator;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.spigothost.validator.SpigotConfigValidator;

import java.nio.file.Path;

/**
 * Configuration validator for TextFormatter Suite on Spigot.
 * Delegates to the shared {@link ConfigValidator} in host module.
 */
public final class SpigotConfigValidator {

    private SpigotConfigValidator() {}

    public static void validate(HostConfig config, PluginLogger logger, java.nio.file.Path configDir) {
        me.majhrs16.suite.host.config.ConfigValidator.validate(config, logger, configDir);
    }
}
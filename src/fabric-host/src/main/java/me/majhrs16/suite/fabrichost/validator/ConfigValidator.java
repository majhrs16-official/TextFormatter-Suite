package me.majhrs16.suite.fabrichost.validator;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.config.ConfigValidator;
import me.majhrs16.suite.host.config.HostConfig;

import java.nio.file.Path;

/**
 * Configuration validator for TextFormatter Suite on Fabric.
 * Delegates to the shared {@link ConfigValidator} in host module.
 */
public final class ConfigValidator {

    private ConfigValidator() {}

    public static void validate(HostConfig config, PluginLogger logger, Path configDir) {
        ConfigValidator.validate(config, logger, configDir);
    }
}
package me.majhrs16.suite.host;

import java.nio.file.Path;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.RateLimiter;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.render.ChatDelivery;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.chattranslator.BackendConfig;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.iflow.vm.VmConfigLoader;
import me.majhrs16.suite.iflow.vm.engine.VmPolicyEngine;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.textformatter.ChannelConfig;
import me.majhrs16.suite.textformatter.TextFormatter;
import me.majhrs16.suite.textformatter.TextFormatterMessageRenderer;
import me.majhrs16.suite.textformatter.TextFormatters;

/**
 * Test-only replacement for the removed {@code SuiteHost.bootstrap(...)}.
 *
 * <p>The production host no longer knows feature types: packages supply the
 * router, formatter and translators. Tests still need a compact pipeline
 * wired from the real feature implementations, so the wiring lives here
 * instead of in {@code src/main}.</p>
 */
final class HostFixture {

    private HostFixture() {
    }

    static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                               PluginLogger logger) {
        return bootstrap(configDir, permissions, null, logger, null);
    }

    static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                               PlaceholderResolver placeholders, PluginLogger logger) {
        return bootstrap(configDir, permissions, placeholders, logger, null);
    }

    static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                               PlaceholderResolver placeholders, PluginLogger logger,
                               ChatDelivery delivery) {
        ConfigLoader.LoadResult<HostConfig> configResult =
                ConfigLoader.loadConfig(configDir, logger);
        if (!configResult.isValid() && logger != null) {
            configResult.errors().forEach(error -> logger.error("Config error: " + error));
        }
        HostConfig config = configResult.config();

        ChannelConfig.LoadResult<ChannelRegistry> channelsResult =
                ChannelConfig.load(configDir);
        if (!channelsResult.isValid() && logger != null) {
            channelsResult.errors().forEach(error -> logger.error("Channel config error: " + error));
        }
        ChannelRegistry channels = channelsResult.config();

        TranslatorManager translators = BackendConfig.load(configDir);
        TranslationService translation = new TranslationService(translators);
        Router router = router(configDir, channels, permissions, placeholders, translation, logger);

        TextFormatter formatter = TextFormatters.create(channels, translation, placeholders, logger);
        TextFormatterMessageRenderer renderer = new TextFormatterMessageRenderer(formatter);
        return new SuiteHost(config, channels, translation, router,
                renderer, renderer, logger, delivery);
    }

    private static Router router(Path configDir, ChannelRegistry channels,
                                 PermissionChecker permissions, PlaceholderResolver placeholders,
                                 TranslationService translation, PluginLogger logger) {
        ProgramImage program = VmConfigLoader.loadProgram(configDir, logger);
        VmPolicyEngine vm = new VmPolicyEngine(channels, permissions, new RateLimiter());
        vm.setProgram(program);
        return vm;
    }
}

package me.majhrs16.suite.host;

import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.host.port.ChatDelivery;
import me.majhrs16.suite.iflow.Router;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ModuleLoader;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.textformatter.TextFormatter;
import me.majhrs16.suite.textformatter.TextFormatters;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.textformatter.ChannelConfig;
import me.majhrs16.suite.textformatter.ChannelConfig.LoadResult;
import me.majhrs16.suite.chattranslator.ChatTranslator;
import me.majhrs16.suite.chattranslator.ChatTranslatorConfig;
import me.majhrs16.suite.chattranslator.BackendConfig;
import me.majhrs16.suite.api.spi.TranslatorManager;

import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Kernel-driven bootstrap that discovers and resolves all suite modules via
 * {@link ModuleLoader} + {@link ModuleGraph}, then assembles the
 * {@link SuiteHost} with the resolved dependency order.
 *
 * <p>This replaces the manual wiring in {@link SuiteHost#bootstrap} with a
 * kernel-driven resolution that honors module dependencies declared in
 * {@link me.majhrs16.suite.api.ModuleDescriptor}.
 *
 * <p><b>Important:</b> Per {@link me.majhrs16.suite.api.Module} contract,
 * modules are <em>not instantiated</em> here - they are SPI descriptors
 * for the dependency graph only. Services activate through their own
 * platform entry points (Spigot plugin / Fabric mod). This bootstrap only
 * uses the kernel to validate the dependency graph and produce a resolved
 * activation order for documentation/ordering purposes.</p>
 */
public final class SuiteBootstrap {

    private SuiteBootstrap() {}

    /**
     * Bootstraps the full suite from a config directory, discovering and
     * resolving all modules present on the classpath via {@link ModuleLoader}
     * and {@link ModuleGraph}.
     *
     * <p>This validates the dependency graph and returns a SuiteHost wired
     * with the core components (Router, TextFormatter). The module
     * descriptors are used only for graph resolution - services activate
     * through their own platform entry points per the Module contract.</p>
     */
    public static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                                      me.majhrs16.suite.api.spi.PlaceholderResolver placeholders,
                                      PluginLogger logger) {
        return bootstrap(configDir, permissions, placeholders, logger, ServiceLoader.load(ChatDelivery.class).findFirst().orElse(null));
    }

    /**
     * Bootstraps with an explicitly provided {@link ChatDelivery} implementation.
     * Loads chattranslator config and backends, creates ChatTranslator facade.
     */
    public static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                                      me.majhrs16.suite.api.spi.PlaceholderResolver placeholders,
                                      PluginLogger logger,
                                      ChatDelivery chatDelivery) {
        ConfigLoader.LoadResult<HostConfig> configResult = ConfigLoader.loadConfig(configDir, logger);
        if (!configResult.isValid()) {
            if (logger != null) {
                for (String err : configResult.errors()) {
                    logger.error("Config error: " + err);
                }
            }
        }
        HostConfig config = configResult.config();

        LoadResult<ChannelRegistry> channelsResult = ChannelConfig.load(configDir);
        if (!channelsResult.isValid()) {
            if (logger != null) {
                for (String err : channelsResult.errors()) {
                    logger.error("Channel config error: " + err);
                }
            }
        }
        ChannelRegistry channels = channelsResult.config();

        // Load ChatTranslator configuration and backends
        ChatTranslatorConfig.Config chatTranslatorConfig = ChatTranslatorConfig.load(configDir);
        TranslatorManager translatorManager = BackendConfig.load(configDir);
        TranslationService translationService = new TranslationService(translatorManager);
        ChatTranslator chatTranslator = new ChatTranslator(translatorManager, translationService, chatTranslatorConfig);

        // 2. Discover and resolve modules via kernel (descriptors only).
        // iFlow engines are mutually exclusive; keep only the configured one.
        List<Module> modules = IflowEngineSelector.select(ModuleLoader.discover(), config.engine());
        Environment env = Environment.current();
        ModuleGraph graph = new ModuleGraph(env);
        ResolutionResult result = graph.resolve(modules);

        // 3. Validate all modules resolved (no cycles, unmet requirements, etc.)
        if (!result.allResolved()) {
            StringBuilder sb = new StringBuilder("Module resolution failed: ");
            for (Module module : result.rejected()) {
                sb.append(module.descriptor().name())
                  .append(": ")
                  .append(result.reasonOf(module))
                  .append("; ");
            }
            throw new IllegalStateException(sb.toString());
        }

        // Select the iFlow runtime (vm by default, firewall via iflow.engine.kind)
        Router router = RouterFactory.create(config, configDir, channels, permissions,
            placeholders, translationService, logger);

        TextFormatter formatter = TextFormatters.create(channels, translationService, placeholders, logger);
        return new SuiteHost(config, channels, translationService, chatTranslator, router, formatter, logger, chatDelivery);
    }

    /**
     * Bootstraps with default translation service and no placeholder resolver.
     */
    public static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                                      PluginLogger logger) {
        return bootstrap(configDir, permissions, null, logger);
    }
}
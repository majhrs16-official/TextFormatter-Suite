package me.majhrs16.suite.host;

import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.iflow.DefaultRouter;
import me.majhrs16.suite.iflow.Router;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ModuleLoader;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.textformatter.TextFormatters;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

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
                                      TranslationService translation,
                                      me.majhrs16.suite.api.spi.PlaceholderResolver placeholders,
                                      PluginLogger logger) {
        // 1. Load platform config
        HostConfig config = ConfigLoader.loadConfig(configDir);
        ChannelRegistry channels = ConfigLoader.loadChannels(configDir);

        // 2. Discover and resolve modules via kernel (descriptors only)
        List<Module> modules = ModuleLoader.discover();
        Environment env = Environment.current();
        ModuleGraph graph = new ModuleGraph(env);
        me.majhrs16.suite.kernel.ResolutionResult result = graph.resolve(modules);

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

        // 4. Wire core components (these are the actual services, not Module descriptors)
        me.majhrs16.suite.iflow.Router router = new me.majhrs16.suite.iflow.DefaultRouter(channels, permissions);
        me.majhrs16.suite.textformatter.TextFormatter formatter = me.majhrs16.suite.textformatter.TextFormatters.create(channels, translation, placeholders, logger);

        // 5. Create SuiteHost with core components
        return new SuiteHost(config, channels, translation, router, formatter, logger);
    }

    /**
     * Bootstraps with default translation service and no placeholder resolver.
     */
    public static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                                      TranslationService translation, PluginLogger logger) {
        return bootstrap(configDir, permissions, translation, null, logger);
    }
}

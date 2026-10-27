package me.majhrs16.suite.host;

import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.iflow.Router;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.channel.RateLimiter;
import me.majhrs16.suite.iflow.firewall.DefaultRouter;
import me.majhrs16.suite.iflow.firewall.RuleConfigLoader;
import me.majhrs16.suite.iflow.firewall.RuleExpressionEvaluator;
import me.majhrs16.suite.iflow.vm.VmConfigLoader;
import me.majhrs16.suite.iflow.vm.engine.VmPolicyEngine;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Selects the iFlow runtime ({@code iflow-vm} or {@code iflow-firewall}) from
 * {@code iflow.engine.kind} in {@code config.yml}.
 *
 * <p>Both modules are optional peers: the host depends on each one, and this
 * factory is the only seam that chooses between them. Neither module imports
 * the other.</p>
 */
public final class RouterFactory {

    private RouterFactory() {
    }

    /**
     * Creates the configured router.
     *
     * @param config       resolved host config (selects the engine)
     * @param configDir    suite data folder (for {@code policy.flow} / {@code rules.yml})
     * @param channels     channel registry
     * @param permissions  platform permission checker
     * @param placeholders placeholder resolver (legacy SpEL only)
     * @param translation  translation service (legacy SpEL only)
     * @param logger       logger, may be {@code null}
     * @return a VM or firewall router, never {@code null}
     */
    public static Router create(HostConfig config,
                                Path configDir,
                                ChannelRegistry channels,
                                PermissionChecker permissions,
                                PlaceholderResolver placeholders,
                                TranslationService translation,
                                PluginLogger logger) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(configDir, "configDir");
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(permissions, "permissions");

        if (config.engine() == HostConfig.Engine.FIREWALL) {
            RuleExpressionEvaluator evaluator = new RuleExpressionEvaluator(placeholders, translation, logger);
            DefaultRouter firewall = new DefaultRouter(channels, permissions::has, evaluator);
            firewall.setRules(RuleConfigLoader.loadRules(configDir, logger));
            if (logger != null) {
                logger.info("iFlow engine: firewall (legacy SpEL rules)");
            }
            return new FirewallRouterAdapter(firewall);
        }

        ProgramImage program = VmConfigLoader.loadProgram(configDir, logger);
        VmPolicyEngine vm = new VmPolicyEngine(channels, permissions, new RateLimiter());
        vm.setProgram(program);
        if (logger != null) {
            logger.info("iFlow engine: vm (.flow)");
        }
        return vm;
    }
}

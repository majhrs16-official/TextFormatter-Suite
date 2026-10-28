package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.TranslationService;

/**
 * Runtime activator of the legacy {@code iflow-firewall} package.
 *
 * <p>Builds the SpEL firewall from the shared {@code channel-api} service,
 * loads {@code rules.yml} from the package configuration directory and
 * publishes the router. The router owns a rate limiter, so it is tracked as a
 * module resource.</p>
 */
public final class IflowFirewallActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-firewall")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("router", SemVer.of(2, 1, 0)))
                .require(Requirement.atLeast("channel-api", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        ChannelRegistry channels = context.services().find(ChannelRegistry.class)
                .orElseThrow(() -> new IllegalStateException(
                        "iflow-firewall requires the channel-api capability"));
        PermissionChecker permissions = context.services().find(PermissionChecker.class)
                .orElse(PermissionChecker.ALLOW_ALL);
        PlaceholderResolver placeholders = context.services()
                .find(PlaceholderResolver.class)
                .orElse(null);
        TranslationService translation = context.services()
                .find(TranslationService.class)
                .orElse(null);

        RuleExpressionEvaluator evaluator =
                new RuleExpressionEvaluator(placeholders, translation, context.logger());
        DefaultRouter router = new DefaultRouter(channels, permissions, evaluator);
        router.setRules(RuleConfigLoader.loadRulesFromConfigDir(
                context.configDir(), context.logger()));

        context.services().register(Router.class, router);
        context.services().register(DefaultRouter.class, router);
        context.resources().track(router);
        context.logger().info("iFlow firewall engine ready with "
                + router.rules().size() + " rules");
    }

    @Override
    public void stop() {
        // The module scope closes the tracked DefaultRouter.
    }
}

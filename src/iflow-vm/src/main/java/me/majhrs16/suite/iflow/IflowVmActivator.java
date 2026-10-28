package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.RateLimiter;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.iflow.vm.VmConfigLoader;
import me.majhrs16.suite.iflow.vm.engine.VmPolicyEngine;

/**
 * Runtime activator of the {@code iflow-vm} package.
 *
 * <p>Builds the VM policy engine from the shared {@code channel-api} service,
 * loads {@code policy.flow} from the package configuration directory and
 * publishes the engine as a {@link Router} service. The engine owns an
 * execution pool and a rate limiter, so it is tracked as a module resource.</p>
 */
public final class IflowVmActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-vm")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("iflow-vm", SemVer.of(2, 1, 0)))
                .require(Requirement.atLeast("channel-api", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        ChannelRegistry channels = context.services().find(ChannelRegistry.class)
                .orElseThrow(() -> new IllegalStateException(
                        "iflow-vm requires the channel-api capability"));
        PermissionChecker permissions = context.services().find(PermissionChecker.class)
                .orElse(PermissionChecker.ALLOW_ALL);

        VmPolicyEngine engine = new VmPolicyEngine(channels, permissions, new RateLimiter());
        try {
            engine.setProgram(VmConfigLoader.loadProgram(context.configDir(), context.logger()));
            context.services().register(Router.class, engine);
            context.services().register(VmPolicyEngine.class, engine);
            context.resources().track(engine);
            context.logger().info("iFlow VM engine ready");
        } catch (RuntimeException failure) {
            engine.close();
            throw failure;
        }
    }

    @Override
    public void stop() {
        // The module scope closes the tracked VmPolicyEngine.
    }
}

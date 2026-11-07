package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.IflowBackend;
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
 * <p>Builds the VM policy engine from the optional {@code ChannelRegistry}
 * service, loads {@code policy.flow} from the package configuration directory
 * and publishes the engine as a {@link Router} service. Without
 * {@code textformatter} the engine uses the neutral empty registry. The engine
 * owns an execution pool and a rate limiter, so it is tracked as a module
 * resource.</p>
 */
public final class IflowVmActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-vm")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(1, 0, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("iflow-vm", SemVer.of(1, 0, 0)))
                .provide(Capability.of("router", SemVer.of(1, 0, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        ChannelRegistry channels = context.services().find(ChannelRegistry.class)
                .orElseGet(() -> {
                    context.logger().warn("iflow-vm: no channel catalog; using the neutral empty registry");
                    return ChannelRegistry.empty();
                });
        PermissionChecker permissions = context.services().find(PermissionChecker.class)
                .orElse(PermissionChecker.ALLOW_ALL);

        VmPolicyEngine engine = new VmPolicyEngine(channels, permissions, new RateLimiter());
        try {
            engine.setProgram(VmConfigLoader.loadProgram(context.configDir(), context.logger()));
            context.services().register(Router.class, engine);
            context.services().register(VmPolicyEngine.class, engine);
            context.services().register(IflowBackend.class, new IflowBackend() {
                @Override
                public String name() {
                    return "iflow-vm";
                }

                @Override
                public Router router() {
                    return engine;
                }

                @Override
                public boolean isAvailable() {
                    return true;
                }
            });
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

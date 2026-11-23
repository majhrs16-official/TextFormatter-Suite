package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.IflowBackend;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.iflow.vm.engine.v3.Iflow32Router;

/**
 * Runtime activator of the {@code iflow-32} package.
 *
 * <p>Builds the v3 router from the optional {@code ChannelRegistry} service and
 * loads {@code policy.flow} from the package configuration directory. Publishes
 * the router and an {@link IflowBackend} named {@code iflow-32}. The hub selects
 * this engine only by explicit configuration: it never enters the 16-bit
 * fallback line and it is never mixed with {@code iflow-16}.</p>
 *
 * <p>A missing or invalid ROM does not abort activation: the backend is
 * published as unavailable and the hub applies its configured policy. Every
 * route attempted against it fails closed with an explicit reason.</p>
 */
public final class Iflow32Activator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-32")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(1, 0, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("iflow-32", SemVer.of(1, 0, 0)))
                .provide(Capability.of("router", SemVer.of(1, 0, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        ChannelRegistry channels = context.services().find(ChannelRegistry.class)
                .orElseGet(() -> {
                    context.logger().warn(
                            "iflow-32: no channel catalog; using the neutral empty registry");
                    return ChannelRegistry.empty();
                });
        PermissionChecker permissions = context.services().find(PermissionChecker.class)
                .orElse(PermissionChecker.ALLOW_ALL);

        Iflow32Router router = new Iflow32Router(
                context.configDir(), channels, permissions, context.logger());
        // The sync bus may activate after this engine; resolve it on every call.
        router.syncBus(() -> context.services().find(SyncBus.class).orElse(null));

        context.services().register(Router.class, router);
        context.services().register(Iflow32Router.class, router);
        context.services().register(IflowBackend.class, new IflowBackend() {
            @Override
            public String name() {
                return "iflow-32";
            }

            @Override
            public Router router() {
                return router;
            }

            @Override
            public boolean isAvailable() {
                return router.isAvailable();
            }
        });
        context.resources().track(router);

        if (router.isAvailable()) {
            context.logger().info("iFlow 32-bit VM engine ready");
        } else {
            context.logger().warn("iFlow 32-bit VM engine unavailable: {}",
                    router.lastError());
        }
    }

    @Override
    public void stop() {
        // The module scope closes the tracked Iflow32Router.
    }
}

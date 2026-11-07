package me.majhrs16.suite.inworld;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.inworld.InWorldService;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;

/**
 * Runtime activator of the {@code inworld} package.
 *
 * <p>Publishes the neutral {@link InWorldHandler} and declares the in-world
 * capabilities. The platform listener lives in the host adapter; activation
 * does not register Bukkit/Fabric events by itself.</p>
 */
public final class InWorldActivator implements ModuleActivator {

    private InWorldHandler handler;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("inworld")
            .version(SemVer.of(1, 0, 0))
            .contractVersion(SemVer.of(1, 0, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("inworld-signs", SemVer.of(1, 0, 0)))
            .provide(Capability.of("inworld-containers", SemVer.of(1, 0, 0)))
            .provide(Capability.of("inworld-books", SemVer.of(1, 0, 0)))
            .provide(Capability.of("channel-world", SemVer.of(1, 0, 0)))
            .provide(Capability.of("channel-radius", SemVer.of(1, 0, 0)))
            .provide(Capability.of("click-hover", SemVer.of(1, 0, 0)))
            .build();
    }

    @Override
    public void start(ModuleContext context) {
        ChannelRegistry channels = context.services().find(ChannelRegistry.class)
            .orElseGet(() -> {
                context.logger().warn("inworld: no channel catalog; using the neutral empty registry");
                return ChannelRegistry.empty();
            });
        handler = new InWorldHandler(channels, context.logger());
        context.services().register(InWorldHandler.class, handler);
        context.services().register(InWorldService.class, handler);
        context.logger().info("In-world domain ready");
    }

    @Override
    public void stop() {
        handler = null;
    }
}

package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncSink;

/**
 * Runtime activator of the {@code sync-http} package.
 *
 * <p>Reads {@code config.yml} from the module config directory and registers an
 * {@link HttpSink} when an outbound URL is configured. An incomplete
 * configuration leaves the package active without a sink.</p>
 */
public final class SyncHttpActivator implements ModuleActivator {

    private SyncBus bus;
    private SyncSink sink;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-http")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
                .require(Requirement.atLeast("sync-bus", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        SyncBus resolved = context.services().find(SyncBus.class)
                .orElseThrow(() -> new IllegalStateException(
                        "sync-http requires the sync-bus service"));

        HttpConfig config = HttpConfig.loadFromConfigDir(context.configDir());
        if (!config.usable()) {
            context.logger().info("HTTP sync sink is not configured");
            return;
        }

        HttpSink created = new HttpSink(
                config.outboundUrl(),
                config.inboundPort(),
                config.inboundPath(),
                config.bind(),
                config.authToken().isBlank() ? null : config.authToken(),
                config.hmacSecret().isBlank() ? null : config.hmacSecret());
        resolved.register(created);
        context.logger().info("HTTP sync sink registered for " + config.outboundUrl());

        this.bus = resolved;
        this.sink = created;
    }

    @Override
    public void stop() {
        if (bus != null && sink != null) {
            bus.unregister(sink.name());
        }
        bus = null;
        sink = null;
    }
}

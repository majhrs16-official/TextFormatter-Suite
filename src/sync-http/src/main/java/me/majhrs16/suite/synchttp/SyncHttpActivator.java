package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncSink;

/**
 * Runtime activator of the {@code sync-http} package.
 *
 * <p>Reads {@code config.yml} from the module config directory and publishes an
 * {@link HttpSink} when an outbound URL is configured. An incomplete
 * configuration leaves the package active without a sink. The {@code sync-bus}
 * discovers the published {@link SyncSink}, so this package activates whether
 * or not the bus is installed.</p>
 */
public final class SyncHttpActivator implements ModuleActivator {

    private SyncBus bus;
    private HttpSink sink;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-http")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(1, 0, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("sync-sink", SemVer.of(1, 0, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
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
        context.services().register(SyncSink.class, created);
        this.bus = context.services().find(SyncBus.class).orElse(null);
        this.sink = created;
        context.logger().info("HTTP sync sink published for " + config.outboundUrl());
    }

    @Override
    public void stop() {
        // The module owns the resources it created: the HttpClient selector
        // thread and the inbound server must be released before ModuleHost
        // verifies that the module thread group is empty. The bus also stops
        // the sink on ModuleDeactivatedEvent; both stops are idempotent.
        if (sink == null) {
            return;
        }
        if (bus != null) {
            bus.unregister(sink.name());
        }
        sink.stop();
        sink = null;
        bus = null;
    }
}

package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.SyncBus;

/**
 * Runtime activator of the {@code sync-bus} package.
 *
 * <p>Creates the bus, publishes it in the service registry and starts it.
 * Sink packages resolve {@link SyncBus} from the registry and register
 * themselves; when the bus is already running the sink starts immediately,
 * so a package installed later becomes active without restarting the host.</p>
 */
public final class SyncBusActivator implements ModuleActivator {

    private DefaultSyncBus bus;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-bus")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("sync-bus", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) throws Exception {
        DefaultSyncBus created = new DefaultSyncBus(context.logger());
        context.services().register(SyncBus.class, created);
        context.resources().track(created::stop);
        created.start();
        this.bus = created;
        context.logger().info("Sync bus ready");
    }

    @Override
    public void stop() {
        if (bus != null) {
            bus.stop();
            bus = null;
        }
    }
}

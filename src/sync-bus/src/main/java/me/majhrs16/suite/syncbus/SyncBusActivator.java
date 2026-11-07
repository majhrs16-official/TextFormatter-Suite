package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.pipeline.PipelineStage;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleActivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.ModuleDeactivatedEvent;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.SyncBus;

/**
 * Runtime activator of the {@code sync-bus} package.
 *
 * <p>Creates the bus, publishes it in the service registry and starts it.
 * Sink packages publish their {@link me.majhrs16.suite.api.spi.SyncSink}
 * services instead of looking up the bus; the activator indexes those services
 * at start and refreshes the index on
 * {@link ModuleActivatedEvent}/{@link ModuleDeactivatedEvent}, mirroring the
 * iFlow hub. A sink installed later therefore becomes active without
 * restarting the host, and a sink package no longer requires the bus to
 * activate.</p>
 */
public final class SyncBusActivator implements ModuleActivator {

    private DefaultSyncBus bus;
    private EventBus.Subscription activatedSubscription;
    private EventBus.Subscription deactivatedSubscription;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-bus")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(1, 0, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("sync-bus", SemVer.of(1, 0, 0)))
                .provide(Capability.of("stage:sync", SemVer.of(1, 0, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) throws Exception {
        ServiceRegistry registry = context.services();
        EventBus eventBus = context.events();
        DefaultSyncBus created = new DefaultSyncBus(context.logger());
        EventBus.Subscription activated = null;
        EventBus.Subscription deactivated = null;
        try {
            registry.register(SyncBus.class, created);
            registry.register(PipelineStage.class, new SyncStage(created));
            activated = eventBus.subscribe(ModuleActivatedEvent.class,
                    event -> created.discoverSinks(registry));
            deactivated = eventBus.subscribe(ModuleDeactivatedEvent.class,
                    event -> created.discoverSinks(registry));
            context.resources().track(created::stop);
            context.resources().track(activated);
            context.resources().track(deactivated);
            created.start();
            created.discoverSinks(registry);
        } catch (Exception failure) {
            if (activated != null) {
                activated.close();
            }
            if (deactivated != null) {
                deactivated.close();
            }
            created.stop();
            throw failure;
        }
        this.bus = created;
        this.activatedSubscription = activated;
        this.deactivatedSubscription = deactivated;
        context.logger().info("Sync bus ready");
    }

    @Override
    public void stop() {
        if (activatedSubscription != null) {
            activatedSubscription.close();
            activatedSubscription = null;
        }
        if (deactivatedSubscription != null) {
            deactivatedSubscription.close();
            deactivatedSubscription = null;
        }
        if (bus != null) {
            bus.stop();
            bus = null;
        }
    }
}

package me.majhrs16.suite.syncvelocity;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleActivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.ModuleDeactivatedEvent;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.velocity.VelocityConnection;
import me.majhrs16.suite.api.velocity.VelocityConnectionFactory;

import java.util.List;

/**
 * Runtime activator of the neutral {@code sync-velocity-core} package.
 *
 * <p>The package activates without a Velocity adapter. It subscribes to module
 * lifecycle events and attaches the sink when a matching
 * {@link VelocityConnectionFactory} appears. If that adapter is unloaded, the
 * sink is removed before the adapter classloader can leak.</p>
 */
public final class SyncVelocityActivator implements ModuleActivator {

    private SyncBus bus;
    private ServiceRegistry services;
    private EventBus events;
    private PluginLogger logger;
    private VelocityConfig config;
    private VelocityConnectionFactory factory;
    private VelocitySink sink;
    private EventBus.Subscription activatedSubscription;
    private EventBus.Subscription deactivatedSubscription;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-velocity-core")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
                .require(Requirement.atLeast("sync-bus", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        this.bus = context.services().find(SyncBus.class)
                .orElseThrow(() -> new IllegalStateException(
                        "sync-velocity requires the sync-bus service"));
        this.services = context.services();
        this.events = context.events();
        this.logger = context.logger();
        this.config = VelocityConfig.loadFromConfigDir(context.configDir());
        if (!config.enabled) {
            logger.info("Velocity sync sink is disabled by config");
            return;
        }

        activatedSubscription = events.subscribe(
                ModuleActivatedEvent.class, event -> tryAttach());
        deactivatedSubscription = events.subscribe(
                ModuleDeactivatedEvent.class, event -> detachIfAdapterGone());
        tryAttach();
    }

    @Override
    public synchronized void stop() {
        if (activatedSubscription != null) {
            activatedSubscription.close();
            activatedSubscription = null;
        }
        if (deactivatedSubscription != null) {
            deactivatedSubscription.close();
            deactivatedSubscription = null;
        }
        detach();
        bus = null;
        services = null;
        events = null;
        logger = null;
        config = null;
    }

    private synchronized void tryAttach() {
        if (sink != null || bus == null || services == null) {
            return;
        }
        List<VelocityConnectionFactory> factories =
                services.findAll(VelocityConnectionFactory.class);
        VelocityConnectionFactory selected = factories.stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(config.transport))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            logger.warn("Velocity transport '" + config.transport
                    + "' is not installed; available adapters: "
                    + factories.stream().map(VelocityConnectionFactory::name).toList());
            return;
        }

        VelocityConnection connection = selected.create(config.settings(), logger);
        VelocitySink created;
        try {
            created = new VelocitySink(connection, config, config.settings(), logger);
        } catch (RuntimeException failure) {
            connection.close();
            logger.warn("Velocity sink could not be created: " + failure.getMessage());
            return;
        }
        try {
            bus.register(created);
        } catch (RuntimeException failure) {
            created.stop();
            logger.warn("Velocity sink could not be registered: " + failure.getMessage());
            return;
        }
        this.factory = selected;
        this.sink = created;
        logger.info("Velocity sync sink registered with adapter " + selected.name());
    }

    private synchronized void detachIfAdapterGone() {
        if (sink == null || services == null) {
            return;
        }
        boolean stillPresent = services.findAll(VelocityConnectionFactory.class)
                .stream()
                .anyMatch(candidate -> candidate == factory);
        if (!stillPresent) {
            logger.info("Velocity transport adapter was unloaded; removing the sink");
            detach();
        }
    }

    private void detach() {
        if (bus != null && sink != null) {
            bus.unregister(sink.name());
        }
        sink = null;
        factory = null;
    }
}

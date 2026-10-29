package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.discord.DiscordConnectionFactory;
import me.majhrs16.suite.api.discord.DiscordSettings;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleActivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.ModuleDeactivatedEvent;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncSink;

import java.util.List;

/**
 * Runtime activator of the neutral {@code sync-discord} package.
 *
 * <p>The package activates even when no transport adapter is installed yet:
 * it subscribes to module lifecycle events and attaches the sink as soon as a
 * {@link DiscordConnectionFactory} appears. When the selected adapter is
 * deactivated, the sink is unregistered so no classloader from the unloaded
 * adapter stays reachable.</p>
 *
 * <p>Activation never waits for the remote Discord gateway; that is the
 * adapter's non-blocking responsibility.</p>
 */
public final class SyncDiscordActivator implements ModuleActivator {

    private SyncBus bus;
    private ServiceRegistry services;
    private EventBus events;
    private PluginLogger logger;
    private DiscordConfig config;
    private DiscordConnectionFactory factory;
    private SyncSink sink;
    private EventBus.Subscription activatedSubscription;
    private EventBus.Subscription deactivatedSubscription;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-discord")
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
                        "sync-discord requires the sync-bus service"));
        this.services = context.services();
        this.events = context.events();
        this.logger = context.logger();
        this.config = DiscordConfig.loadFromConfigDir(context.configDir());
        if (!config.usable()) {
            logger.info("Discord sync sink is not configured");
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
        List<DiscordConnectionFactory> factories =
                services.findAll(DiscordConnectionFactory.class);
        DiscordConnectionFactory selected = factories.stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(config.transport()))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            logger.warn("Discord transport '" + config.transport()
                    + "' is not installed; available adapters: "
                    + factories.stream().map(DiscordConnectionFactory::name).toList());
            return;
        }

        DiscordSink created = new DiscordSink(
                selected,
                new DiscordSettings(config.token(), config.channelId()),
                logger);
        try {
            bus.register(created);
        } catch (RuntimeException failure) {
            logger.warn("Discord sink could not be registered: " + failure.getMessage());
            return;
        }
        this.factory = selected;
        this.sink = created;
        logger.info("Discord sync sink registered with adapter " + selected.name());
    }

    private synchronized void detachIfAdapterGone() {
        if (sink == null || services == null) {
            return;
        }
        boolean stillPresent = services.findAll(DiscordConnectionFactory.class)
                .stream()
                .anyMatch(candidate -> candidate == factory);
        if (!stillPresent) {
            logger.info("Discord transport adapter was unloaded; removing the sink");
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

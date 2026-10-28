package me.majhrs16.suite.observability;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.observability.health.HealthCheckRegistry;

import java.io.IOException;

/**
 * Runtime activator of the {@code observability} package.
 *
 * <p>Publishes the health registry and, when enabled, the metrics facade with
 * its Prometheus endpoint. The host debug endpoint is not part of this package:
 * it lives in {@code host.debug} because it needs host state.</p>
 */
public final class ObservabilityActivator implements ModuleActivator {

    private Observability observability;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("observability")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("observability", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        ObservabilityConfig config = ObservabilityConfig.loadFromConfigDir(context.configDir());

        HealthCheckRegistry registry = new HealthCheckRegistry();
        Observability.registerDefaultChecks(registry);
        context.services().register(HealthCheckRegistry.class, registry);

        if (!config.usable()) {
            context.logger().info("Observability metrics endpoint is disabled");
            return;
        }

        Observability created;
        try {
            created = new Observability(
                    context.logger(), config.metricsPort(), config.metricsBind(), registry);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot create the metrics endpoint: " + failure.getMessage(), failure);
        }

        context.services().register(Observability.class, created);
        context.resources().track(created::stop);
        created.start();
        this.observability = created;
    }

    @Override
    public void stop() {
        if (observability != null) {
            observability.stop();
            observability = null;
        }
    }
}

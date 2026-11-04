package me.majhrs16.suite.iflow.hub;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.iflow.IflowHub;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleActivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.ModuleDeactivatedEvent;

/**
 * Runtime activator of the {@code iflow} hub package.
 *
 * <p>El hub publica el {@link Router} estable que consume el host y descubre
 * los {@code IflowBackend} que publican los motores. Los motores pueden
 * activarse después del hub, por lo que la selección se refresca con los
 * eventos de ciclo de vida.</p>
 */
public final class IflowActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("iflow", SemVer.of(2, 1, 0)))
            .build();
    }

    @Override
    public void start(ModuleContext context) {
        IflowConfig config = IflowConfig.load(context.configDir(), context.logger());
        IflowBackendManager manager = new IflowBackendManager(context, config);
        manager.refresh();

        IflowHubImpl hub = new IflowHubImpl(manager);
        EventBus.Subscription onActivated = null;
        EventBus.Subscription onDeactivated = null;
        try {
            onActivated = context.events().subscribe(ModuleActivatedEvent.class,
                event -> refresh(manager, context));
            onDeactivated = context.events().subscribe(ModuleDeactivatedEvent.class,
                event -> refresh(manager, context));

            context.services().register(IflowHub.class, hub);
            context.services().register(Router.class, hub);
            context.resources().track(onActivated);
            context.resources().track(onDeactivated);

            if (manager.active() == null) {
                context.logger().warn("iFlow hub ready without backend: " + manager.lastError());
            } else {
                context.logger().info("iFlow hub ready: active=" + hub.activeBackend()
                    + ", available=" + hub.availableBackends());
            }
        } catch (RuntimeException failure) {
            if (onActivated != null) {
                onActivated.close();
            }
            if (onDeactivated != null) {
                onDeactivated.close();
            }
            throw failure;
        }
    }

    @Override
    public void stop() {
        // El ámbito del módulo cierra las suscripciones trackeadas.
    }

    private static void refresh(IflowBackendManager manager, ModuleContext context) {
        try {
            manager.refresh();
        } catch (RuntimeException failure) {
            context.logger().warn("Cannot refresh iFlow backends: " + failure.getMessage());
        }
    }
}

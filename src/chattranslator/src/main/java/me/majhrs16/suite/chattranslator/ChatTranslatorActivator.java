package me.majhrs16.suite.chattranslator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleActivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.ModuleDeactivatedEvent;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslatorProvider;
import me.majhrs16.suite.api.spi.TranslationService;

/**
 * Runtime activator of the {@code chattranslator} package.
 *
 * <p>Builds the translator manager from providers registered by other packages
 * and from providers bundled in its own classloader, then registers the facade
 * and its translation service in the module scope. The service owns a thread
 * pool, so it is tracked as a module resource and closed on deactivation.</p>
 *
 * <p>Provider packages activate after {@code chattranslator} because they
 * require it. The activator subscribes to module lifecycle events and rebuilds
 * the manager when a provider appears or disappears, which keeps the facade
 * usable across hot installs and unloads.</p>
 */
public final class ChatTranslatorActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("chattranslator")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("chattranslator", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        ChatTranslatorConfig.Config config =
                ChatTranslatorConfig.loadFromConfigDir(context.configDir());

        TranslatorManager manager = new TranslatorManager();
        refresh(manager, null, context);

        TranslationService service = new TranslationService(manager);
        ChatTranslator translator = null;
        EventBus.Subscription onActivated = null;
        EventBus.Subscription onDeactivated = null;
        try {
            translator = new ChatTranslator(manager, service, config);

            onActivated = context.events().subscribe(ModuleActivatedEvent.class,
                    event -> refresh(manager, service, context));
            onDeactivated = context.events().subscribe(ModuleDeactivatedEvent.class,
                    event -> refresh(manager, service, context));

            context.services().register(TranslatorManager.class, manager);
            context.services().register(TranslationService.class, service);
            context.services().register(ChatTranslator.class, translator);
            context.resources().track(onActivated);
            context.resources().track(onDeactivated);
            context.resources().track(translator);
            context.logger().info("ChatTranslator ready with "
                    + manager.providers().size() + " providers");
        } catch (RuntimeException failure) {
            if (onActivated != null) {
                onActivated.close();
            }
            if (onDeactivated != null) {
                onDeactivated.close();
            }
            if (translator != null) {
                translator.close();
            } else {
                service.close();
            }
            throw failure;
        }
    }

    @Override
    public void stop() {
        // The module scope closes the tracked subscriptions and ChatTranslator.
    }

    private static void refresh(TranslatorManager manager, TranslationService service,
                                ModuleContext context) {
        try {
            TranslatorManager refreshed = BackendConfig.loadFromConfigDir(
                    context.configDir(), providers(context));
            manager.replaceAll(refreshed.providers());
            if (service != null) {
                service.clearCaches();
            }
            context.logger().debug("ChatTranslator refreshed with "
                    + manager.providers().size() + " providers");
        } catch (RuntimeException failure) {
            context.logger().warn("Cannot refresh translation providers: "
                    + failure.getMessage());
        }
    }

    private static List<TranslatorProvider> providers(ModuleContext context) {
        Map<String, TranslatorProvider> byName = new LinkedHashMap<>();
        for (TranslatorProvider provider : context.services().findAll(TranslatorProvider.class)) {
            byName.putIfAbsent(provider.name(), provider);
        }
        for (TranslatorProvider provider : ServiceLoader.load(
                TranslatorProvider.class, ChatTranslatorActivator.class.getClassLoader())) {
            byName.putIfAbsent(provider.name(), provider);
        }
        return new ArrayList<>(byName.values());
    }
}

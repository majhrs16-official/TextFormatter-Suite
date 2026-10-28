package me.majhrs16.suite.chattranslator;

import java.util.HashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.Translator;
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
        Set<String> names = new HashSet<>();
        for (TranslatorProvider provider : context.services().findAll(TranslatorProvider.class)) {
            addProvider(manager, provider, names, context);
        }
        for (TranslatorProvider provider : ServiceLoader.load(
                TranslatorProvider.class, getClass().getClassLoader())) {
            addProvider(manager, provider, names, context);
        }

        TranslationService service = new TranslationService(manager);
        ChatTranslator translator;
        try {
            translator = new ChatTranslator(manager, service, config);
        } catch (RuntimeException failure) {
            service.close();
            throw failure;
        }

        context.services().register(TranslatorManager.class, manager);
        context.services().register(TranslationService.class, service);
        context.services().register(ChatTranslator.class, translator);
        context.resources().track(translator);
        context.logger().info("ChatTranslator ready with " + manager.providers().size() + " providers");
    }

    @Override
    public void stop() {
        // The module scope closes the tracked ChatTranslator and its service.
    }

    private static void addProvider(TranslatorManager manager, TranslatorProvider provider,
                                    Set<String> names, ModuleContext context) {
        if (!names.add(provider.name())) {
            return;
        }
        try {
            Map<String, Object> providerConfig = Map.of();
            if (!provider.validateConfig(providerConfig)) {
                context.logger().debug("Translator provider " + provider.name()
                        + " is not configured; skipped");
                return;
            }
            Translator translator = provider.create(providerConfig);
            if (translator != null) {
                manager.add(translator);
            }
        } catch (Exception failure) {
            context.logger().warn("Cannot create translator provider " + provider.name()
                    + ": " + failure.getMessage());
        }
    }
}

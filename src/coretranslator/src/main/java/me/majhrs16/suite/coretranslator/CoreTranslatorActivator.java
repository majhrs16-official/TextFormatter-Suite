package me.majhrs16.suite.coretranslator;

import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformHandle;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;

/**
 * Activates the legacy CoreTranslator PlaceholderAPI expansion.
 *
 * <p>The activator itself stays platform-neutral. The Bukkit/PAPI hook is
 * loaded reflectively only when the host reports a Bukkit-family platform, so
 * a mis-installed package fails with a clear log line instead of a
 * {@link NoClassDefFoundError} on Fabric.</p>
 */
@Deprecated
public final class CoreTranslatorActivator implements ModuleActivator {

    private static final String BUKKIT_HOOK =
        "me.majhrs16.suite.coretranslator.CoreTranslatorBukkitHook";

    private final ModuleDescriptor descriptor = CoreTranslatorModule.descriptorDefinition();

    @Override
    public ModuleDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void start(ModuleContext context) {
        TranslationService translations = context.services().find(TranslationService.class)
            .orElseThrow(() -> new IllegalStateException(
                "coretranslator requires the chattranslator package (TranslationService)"));
        UserLanguageStore languages = context.services().find(UserLanguageStore.class).orElse(null);
        ActorDirectory actors = context.services().find(ActorDirectory.class).orElse(null);
        MessageDispatch dispatch = context.services().find(MessageDispatch.class).orElse(null);
        SyncBus syncBus = context.services().find(SyncBus.class).orElse(null);

        CoreTranslatorPlaceholders placeholders = new CoreTranslatorPlaceholders(
            translations::translate,
            languages,
            actors,
            dispatch,
            syncBus,
            context.logger());
        context.services().register(CoreTranslatorPlaceholders.class, placeholders);

        if (dispatch == null) {
            context.logger().warn("CoreTranslator: no MessageDispatch service; "
                + "cot_send/cot_broadcast/cot_clone are disabled");
        }
        if (!isBukkitFamily(context.platform().name())) {
            context.logger().warn("CoreTranslator is Bukkit-only; %cot_* placeholders "
                + "are not registered on platform '" + context.platform().name() + "'");
            return;
        }
        PlatformHandle handle = context.services().find(PlatformHandle.class).orElse(null);
        if (handle == null) {
            context.logger().warn("CoreTranslator: no PlatformHandle; "
                + "%cot_* placeholders are disabled");
            return;
        }
        installBukkitHook(context, handle, placeholders);
    }

    @Override
    public void stop() {
        // The module scope closes the registered service and the PAPI cleanup.
    }

    private static boolean isBukkitFamily(String platform) {
        return "spigot".equalsIgnoreCase(platform)
            || "paper".equalsIgnoreCase(platform)
            || "bukkit".equalsIgnoreCase(platform);
    }

    private static void installBukkitHook(ModuleContext context,
                                          PlatformHandle handle,
                                          CoreTranslatorPlaceholders placeholders) {
        try {
            Class<?> hook = Class.forName(BUKKIT_HOOK, true, CoreTranslatorActivator.class.getClassLoader());
            hook.getMethod("install", ModuleContext.class, PlatformHandle.class,
                    CoreTranslatorPlaceholders.class)
                .invoke(null, context, handle, placeholders);
        } catch (ClassNotFoundException missing) {
            context.logger().warn("CoreTranslator: Bukkit hook not present in the package");
        } catch (ReflectiveOperationException | LinkageError failure) {
            context.logger().warn("CoreTranslator: cannot install PlaceholderAPI expansion: " + failure);
        }
    }
}

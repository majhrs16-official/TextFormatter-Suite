package me.majhrs16.suite.coretranslator;

import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformHandle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit-only part of CoreTranslator.
 *
 * <p>This class deliberately does not reference PlaceholderAPI types. It checks
 * whether the plugin is installed and then loads the PAPI expansion
 * reflectively, so a server without PlaceholderAPI keeps working and logs a
 * single warning instead of failing module activation.</p>
 */
@Deprecated
public final class CoreTranslatorBukkitHook {

    private static final String PAPI_EXPANSION =
        "me.majhrs16.suite.coretranslator.CoreTranslatorPapiExpansion";

    private CoreTranslatorBukkitHook() {
    }

    public static void install(ModuleContext context,
                               PlatformHandle handle,
                               CoreTranslatorPlaceholders placeholders) {
        JavaPlugin plugin;
        try {
            plugin = handle.unwrap(JavaPlugin.class);
        } catch (ClassCastException wrongPlatform) {
            context.logger().warn("CoreTranslator: platform handle is not a Bukkit JavaPlugin");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            context.logger().warn("CoreTranslator: PlaceholderAPI is not installed; "
                + "%cot_* placeholders are disabled");
            return;
        }
        if (!plugin.isEnabled()) {
            context.logger().warn("CoreTranslator: host plugin is not enabled; "
                + "%cot_* placeholders are disabled");
            return;
        }
        try {
            Class<?> expansion = Class.forName(
                PAPI_EXPANSION, true, CoreTranslatorBukkitHook.class.getClassLoader());
            expansion.getMethod("register", JavaPlugin.class,
                    CoreTranslatorPlaceholders.class, ModuleContext.class)
                .invoke(null, plugin, placeholders, context);
        } catch (ClassNotFoundException missing) {
            context.logger().warn("CoreTranslator: PlaceholderAPI expansion class is missing");
        } catch (ReflectiveOperationException | LinkageError failure) {
            context.logger().warn("CoreTranslator: cannot register PlaceholderAPI expansion: " + failure);
        }
    }
}

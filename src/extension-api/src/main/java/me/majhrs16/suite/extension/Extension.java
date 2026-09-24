package me.majhrs16.suite.extension;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents an extension (addon) for TextFormatter Suite.
 * <p>
 * Extensions are dynamically loadable modules that extend suite functionality
 * without requiring a server restart. They run in isolated classloaders and
 * declare their capabilities, dependencies, and provided services via
 * {@code extension.yml}.
 * </p>
 */
public interface Extension {

    /**
     * @return unique identifier (lowercase, kebab-case, e.g. "my-extension")
     */
    String id();

    /**
     * @return human-readable display name
     */
    String name();

    /**
     * @return semantic version of the extension
     */
    SemVer version();

    /**
     * @return SemVer range of suite core-api this extension is compatible with
     */
    SemVer requiredCoreApi();

    /**
     * @return list of extension IDs this extension depends on (load order)
     */
    List<String> dependencies();

    /**
     * @return capabilities this extension provides (e.g. "custom-channel", "translation-provider")
     */
    Set<Capability> providedCapabilities();

    /**
     * @return capabilities this extension requires from other extensions/core
     */
    Set<Capability> requiredCapabilities();

    /**
     * Called when the extension is loaded and initialized.
     * <p>
     * Use this to register event listeners, commands, channels, etc.
     * </p>
     *
     * @param context provides access to suite services (host, dispatcher, etc.)
     */
    void onEnable(ExtensionContext context);

    /**
     * Called when the extension is unloaded/disabled.
     * <p>
     * Clean up resources, unregister listeners, save state.
     * </p>
     */
    void onDisable();

    /**
     * Called when extension configuration changes (hot-reload).
     * <p>
     * Default implementation does nothing. Override to handle config updates.
     * </p>
     */
    default void onConfigReload(ExtensionConfig config) {}

    /**
     * @return metadata for display in web-editor and /suite extensions command
     */
    ExtensionMetadata metadata();

    /**
     * Configuration schema for this extension (JSON Schema draft-07).
     * Used by web-editor for validation and UI generation.
     */
    default String configSchema() {
        return "{}";
    }
}
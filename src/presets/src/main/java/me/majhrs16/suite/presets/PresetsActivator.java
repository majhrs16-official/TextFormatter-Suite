package me.majhrs16.suite.presets;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;

/**
 * Runtime activator of the {@code presets} package.
 *
 * <p>Creates the preset catalog inside the module-private config directory and
 * publishes it as a service. The package does not own host files: a caller
 * supplies a {@link PresetTarget} (for example {@link FilePresetTarget}) when it
 * applies a preset.</p>
 */
public final class PresetsActivator implements ModuleActivator {

    private PresetManager manager;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("presets")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("presets", SemVer.of(2, 1, 0)))
            .build();
    }

    @Override
    public void start(ModuleContext context) {
        manager = new PresetManager(context.configDir());
        context.services().register(PresetManager.class, manager);
        context.logger().info("Preset catalog ready: " + manager.listAll().size() + " presets");
    }

    @Override
    public void stop() {
        manager = null;
    }
}

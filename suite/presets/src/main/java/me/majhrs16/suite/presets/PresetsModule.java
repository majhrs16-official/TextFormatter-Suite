package me.majhrs16.suite.presets;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Presets module: provides preset configurations
 * and the transform engine.
 */
public final class PresetsModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("presets")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("presets", SemVer.of(2, 1, 0)))
            .provide(Capability.of("transform-engine", SemVer.of(2, 1, 0)))
            .build();
    }
}
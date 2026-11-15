package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the iFlow 16-bit VM module: provides the {@code iflow-16} capability.
 * The iFlow 16-bit VM executes legacy policy programs (.flow v2 bytecode) in a 64 KB space.
 */
public final class Iflow16Module implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-16")
            .version(SemVer.of(1, 0, 0))
            .contractVersion(SemVer.of(1, 0, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("iflow-16", SemVer.of(1, 0, 0)))
            .provide(Capability.of("router", SemVer.of(1, 0, 0)))
            .build();
    }
}
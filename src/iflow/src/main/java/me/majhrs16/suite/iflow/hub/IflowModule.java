package me.majhrs16.suite.iflow.hub;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the {@code iflow} hub package.
 */
public final class IflowModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow")
            .version(SemVer.of(1, 0, 0))
            .contractVersion(SemVer.of(1, 0, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("iflow", SemVer.of(1, 0, 0)))
            .build();
    }
}

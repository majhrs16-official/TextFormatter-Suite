package me.majhrs16.suite.kernel;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Kernel module: provides the {@code kernel} capability.
 * The kernel provides module resolution (ModuleGraph), ServiceLoader discovery, and environment.
 */
public final class KernelModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("kernel")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("kernel", SemVer.of(2, 1, 0)))
            .build();
    }
}
package me.majhrs16.suite.manager;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Manager SPI module: provides the {@code manager-spi} capability.
 * The manager-spi module provides module management interfaces (ModuleDescriptor, Capability, etc.).
 */
public final class ManagerSpiModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("manager-spi")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("manager-spi", SemVer.of(2, 1, 0)))
            .build();
    }
}
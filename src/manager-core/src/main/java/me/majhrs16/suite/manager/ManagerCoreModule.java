package me.majhrs16.suite.manager;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Manager Core module: provides the {@code manager-core} capability.
 * The manager-core module provides module installation, resolution, and lifecycle management.
 */
public final class ManagerCoreModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("manager-core")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("manager-core", SemVer.of(2, 1, 0)))
            .require(Requirement.atLeast("manager-spi", SemVer.of(2, 1, 0)))
            .build();
    }
}
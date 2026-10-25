package me.majhrs16.suite.manager;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Manager APT module: provides the {@code manager-apt} capability.
 * The manager-apt module provides annotation processing for module descriptors.
 */
public final class ManagerAptModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("manager-apt")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("manager-apt", SemVer.of(2, 1, 0)))
            .require(Requirement.atLeast("manager-spi", SemVer.of(2, 1, 0)))
            .build();
    }
}
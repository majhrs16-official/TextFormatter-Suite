package me.majhrs16.suite.host;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Host core module: provides the {@code host-core} capability.
 * The host core provides the SuiteHost bootstrap, configuration loading, and pipeline assembly.
 */
public final class HostModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("host")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("host-core", SemVer.of(2, 1, 0)))
            .build();
    }
}
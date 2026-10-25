package me.majhrs16.suite.transport;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Transport module: provides the {@code transport} capability.
 * The transport module provides HTTP transport and message codec utilities.
 */
public final class TransportModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("transport")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("transport", SemVer.of(2, 1, 0)))
            .build();
    }
}
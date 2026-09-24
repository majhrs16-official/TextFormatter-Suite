package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the HTTP sync module: provides the {@code sync-sink}
 * capability. The {@link HttpSink} instance (endpoint, port, path) is
 * assembled by the host from its borde config.
 */
public final class SyncHttpModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-http")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
            .build();
    }
}
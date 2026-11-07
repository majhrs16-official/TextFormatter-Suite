package me.majhrs16.suite.syncsocket;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/** SPI provider for the raw TCP/UDP edge module: provides {@code sync-sink}. */
public final class SyncSocketModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-socket")
            .version(SemVer.of(1, 0, 0))
            .contractVersion(SemVer.of(1, 0, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("sync-sink", SemVer.of(1, 0, 0)))
            .build();
    }
}

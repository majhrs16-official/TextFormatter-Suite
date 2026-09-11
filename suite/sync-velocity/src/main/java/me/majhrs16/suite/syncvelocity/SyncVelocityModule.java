package me.majhrs16.suite.syncvelocity;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Velocity sync module: provides the {@code sync-sink}
 * capability. The {@link VelocitySink} instance connects to a Velocity proxy
 * via the plugin messaging channel.
 */
public final class SyncVelocityModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-velocity")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
            .build();
    }
}
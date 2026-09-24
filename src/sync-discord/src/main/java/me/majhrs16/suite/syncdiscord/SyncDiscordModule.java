package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/** SPI provider for the Discord edge module: provides {@code sync-sink}. */
public final class SyncDiscordModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-discord")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
            .build();
    }
}
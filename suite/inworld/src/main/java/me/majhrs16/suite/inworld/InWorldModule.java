package me.majhrs16.suite.inworld;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the In-World module: provides in-world text interactions
 * like signs, chests, books, WORLD/RADIUS channels, and click/hover interactions.
 */
public final class InWorldModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("inworld")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("inworld-signs", SemVer.of(2, 1, 0)))
            .provide(Capability.of("inworld-containers", SemVer.of(2, 1, 0)))
            .provide(Capability.of("inworld-books", SemVer.of(2, 1, 0)))
            .provide(Capability.of("channel-world", SemVer.of(2, 1, 0)))
            .provide(Capability.of("channel-radius", SemVer.of(2, 1, 0)))
            .provide(Capability.of("click-hover", SemVer.of(2, 1, 0)))
            .build();
    }
}
package me.majhrs16.suite.synctelegram;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/** SPI provider for the Telegram Bot API edge module: provides {@code sync-sink}. */
public final class SyncTelegramModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-telegram")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
            .build();
    }
}
package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI module descriptor for the SyncBus capability.
 * <p>
 * This module provides the {@code sync-bus} capability which enables
 * centralized cross-platform message synchronization.
 * </p>
 */
public final class SyncBusModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-bus")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("sync-bus", SemVer.of(2, 1, 0)))
            .build();
    }
}
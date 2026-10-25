package me.majhrs16.suite.logger;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Logger module: provides the {@code logger} capability.
 * The logger module provides structured logging utilities and destinations.
 */
public final class LoggerModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("logger")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("logger", SemVer.of(2, 1, 0)))
            .build();
    }
}
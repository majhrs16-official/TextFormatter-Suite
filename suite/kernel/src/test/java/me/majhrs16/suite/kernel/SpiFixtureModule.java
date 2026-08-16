package me.majhrs16.suite.kernel;

import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/** Test fixture that is discovered via META-INF/services SPI. */
public final class SpiFixtureModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("spi-fixture")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(3, 0, 0))
                .build();
    }
}
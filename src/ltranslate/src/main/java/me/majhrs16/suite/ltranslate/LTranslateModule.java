package me.majhrs16.suite.ltranslate;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the LTranslate module: provides the {@code translator}
 * capability. The {@link LTranslate} service instance (with its endpoint and
 * API key) is assembled by the platform host from its config.
 */
public final class LTranslateModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("ltranslate")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("translator", SemVer.of(2, 1, 0)))
            .build();
    }
}
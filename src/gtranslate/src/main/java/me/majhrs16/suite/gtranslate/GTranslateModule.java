package me.majhrs16.suite.gtranslate;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the GTranslate module: provides the {@code translator}
 * capability. The actual {@link GTranslate} service is handed out by the
 * platform host, which wires in the {@link Transport} of its choice.
 */
public final class GTranslateModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("gtranslate")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("translator", SemVer.of(2, 1, 0)))
            .build();
    }
}
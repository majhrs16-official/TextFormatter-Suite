package me.majhrs16.suite.coretranslator;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * Deprecated SPI provider for the CoreTranslator bridge module. Exists so a
 * v1.8-style integration can be migrated incrementally; new code must talk to
 * {@code Message}/{@code LegacyBridge} only. The module declares its own
 * capability so a host can detect the presence of the legacy bridge.
 */
@Deprecated
public final class CoreTranslatorModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("coretranslator")
            .version(SemVer.of(1, 0, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("coretranslator-legacy", SemVer.of(1, 0, 0)))
            .build();
    }
}
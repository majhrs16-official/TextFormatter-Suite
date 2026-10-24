package me.majhrs16.suite.chattranslator;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the ChatTranslator module: provides the {@code chattranslator}
 * capability. The actual {@link ChatTranslator} facade is instantiated by the
 * platform host (Spigot/Fabric) which wires in the {@link TranslatorManager}
 * and {@link TranslationService}.
 */
public final class ChatTranslatorModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("chattranslator")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("chattranslator", SemVer.of(2, 1, 0)))
            .build();
    }
}
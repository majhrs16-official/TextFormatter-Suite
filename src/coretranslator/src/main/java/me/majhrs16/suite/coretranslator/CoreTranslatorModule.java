package me.majhrs16.suite.coretranslator;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * Legacy CoreTranslator module: a Bukkit-only PlaceholderAPI expansion that
 * exposes the historical {@code %cot_*} placeholders.
 *
 * <p>This is the real CoreTranslator feature from the previous ChatTranslator
 * project, not the {@code LegacyBridge} shim that briefly lived in
 * {@code src/coretranslator} and was removed in {@code 7dd164a4}. It is
 * intentionally deprecated and independent: new code must use the current
 * {@code Message}/{@code TranslationService} contracts.</p>
 */
@Deprecated
public final class CoreTranslatorModule implements Module {

    /** Base version shared by every suite artifact in the pre-1.0 regime. */
    public static final SemVer VERSION = SemVer.of(1, 0, 0);

    /** @return the immutable descriptor shared by the SPI and activator. */
    public static ModuleDescriptor descriptorDefinition() {
        return ModuleDescriptor.builder("coretranslator")
            .version(VERSION)
            .contractVersion(VERSION)
            .jvmRange(21, 0)
            .provide(Capability.of("coretranslator", VERSION))
            .build();
    }

    @Override
    public ModuleDescriptor descriptor() {
        return descriptorDefinition();
    }
}

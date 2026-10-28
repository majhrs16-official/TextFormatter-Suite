package me.majhrs16.suite.gtranslate;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.TranslatorProvider;

/**
 * Runtime activator of the {@code gtranslate} package.
 *
 * <p>Publishes the Google Translate provider. The provider reads its
 * configuration from {@code chattranslator/translators/*.yml} when the
 * translation facade builds its backends.</p>
 */
public final class GTranslateActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("gtranslate")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("translator", SemVer.of(2, 1, 0)))
                .require(Requirement.atLeast("chattranslator", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        context.services().register(TranslatorProvider.class, new GTranslateProvider());
        context.logger().info("Google Translate provider ready");
    }

    @Override
    public void stop() {
        // The provider holds no thread or native resources.
    }
}

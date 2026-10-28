package me.majhrs16.suite.ltranslate;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.TranslatorProvider;

/**
 * Runtime activator of the {@code ltranslate} package.
 *
 * <p>Publishes the LibreTranslate provider. Its {@code base-url} and optional
 * API key are read from {@code chattranslator/translators/libre.yml}.</p>
 */
public final class LTranslateActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("ltranslate")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("translator", SemVer.of(2, 1, 0)))
                .require(Requirement.atLeast("chattranslator", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        context.services().register(TranslatorProvider.class, new LTranslateProvider());
        context.logger().info("LibreTranslate provider ready");
    }

    @Override
    public void stop() {
        // The provider holds no thread or native resources.
    }
}

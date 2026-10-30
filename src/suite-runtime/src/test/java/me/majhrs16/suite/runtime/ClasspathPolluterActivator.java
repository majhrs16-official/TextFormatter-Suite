package me.majhrs16.suite.runtime;

import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;

/**
 * Activator advertised by the test classpath.
 *
 * <p>It exists only to prove that {@link ModuleHost} discovers activators from
 * the module JAR and not from the runtime classpath, where feature JARs also
 * publish the same service file.</p>
 */
final class ClasspathPolluterActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("classpath-polluter")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .build();
    }

    @Override
    public void start(ModuleContext context) {
    }

    @Override
    public void stop() {
    }
}

package me.majhrs16.suite.tester;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;

/**
 * Runtime activator of the {@code tester} package.
 *
 * <p>Publishes a {@link TestServiceFactory}; the host adapter supplies the
 * live {@link TestProbe} when a command asks for the test service. Activation
 * does not start threads, open sockets or depend on an online server.</p>
 */
public final class TesterActivator implements ModuleActivator {

    private TestServiceFactory factory;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("tester")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("test-service", SemVer.of(2, 1, 0)))
            .require(Requirement.atLeast("channel-api", SemVer.of(2, 1, 0)))
            .require(Requirement.atLeast("router", SemVer.of(2, 1, 0)))
            .require(Requirement.atLeast("formatter", SemVer.of(2, 1, 0)))
            .build();
    }

    @Override
    public void start(ModuleContext context) {
        factory = new TestServiceFactory();
        context.services().register(TestServiceFactory.class, factory);
        context.logger().info("Test service factory ready");
    }

    @Override
    public void stop() {
        factory = null;
    }
}

package me.majhrs16.suite.tester;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Tester module: provides {@code test-service} capability.
 * <p>
 * This module registers a {@link TestService} that can be invoked at runtime
 * via platform commands (e.g. {@code /suite test ...}) to execute stress tests,
 * simulations, and validation scenarios using the LIVE server infrastructure.
 */
public final class TesterModule implements Module {

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
}
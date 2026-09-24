package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider declaring the iFlow module in the kernel graph.
 *
 * <p>iFlow provides the {@code router} capability and <em>requires</em> the
 * {@code channel-api} capability from the TextFormatter module, exercising the
 * cross-module dependency the graph was built for: a host with both jars
 * present resolves both; one missing channel-api leaves iFlow unmet.</p>
 */
public final class IflowModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("router", SemVer.of(2, 1, 0)))
            .require(Requirement.atLeast("channel-api", SemVer.of(2, 1, 0)))
            .build();
    }
}
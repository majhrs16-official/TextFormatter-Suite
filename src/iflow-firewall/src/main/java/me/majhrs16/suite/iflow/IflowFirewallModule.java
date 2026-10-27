package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the legacy {@code iflow-firewall} engine.
 *
 * <p>It provides the {@code router} capability and requires {@code channel-api}
 * from TextFormatter. The three iFlow engines ({@code iflow-vm},
 * {@code iflow-wasm}, {@code iflow-firewall}) are mutually exclusive: only one
 * may be installed, so this descriptor is never active together with
 * {@code iflow-vm} in a supported deployment.</p>
 */
public final class IflowFirewallModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-firewall")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("router", SemVer.of(2, 1, 0)))
            .require(Requirement.atLeast("channel-api", SemVer.of(2, 1, 0)))
            .build();
    }
}
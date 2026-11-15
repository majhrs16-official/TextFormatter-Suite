package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the legacy {@code iflow-firewall} engine.
 *
 * <p>It provides the {@code router} capability and consumes the
 * {@code ChannelRegistry} optionally from the service registry. The iFlow
 * engines ({@code iflow-16}, {@code iflow-32}, {@code iflow-wasm} and
 * {@code iflow-firewall}) are mutually exclusive: only one may be active, so
 * this descriptor is never active together with another engine in a supported
 * deployment.</p>
 */
public final class IflowFirewallModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-firewall")
            .version(SemVer.of(1, 0, 0))
            .contractVersion(SemVer.of(1, 0, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("router", SemVer.of(1, 0, 0)))
            .build();
    }
}

package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider that registers the TextFormatter module in the kernel graph and
 * hands out {@link TextFormatter} instances.
 *
 * <p>The module declares the {@code channel-api} capability at contract
 * {@code 3.0.0}: any consumer (iFlow, platform hosts) requiring it can be
 * wired to this module by the resolver.</p>
 */
public final class TextFormatterModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("textformatter")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("channel-api", SemVer.of(2, 1, 0)))
                .build();
    }
}
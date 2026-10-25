package me.majhrs16.suite.messages;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the Messages module: provides the {@code messages} capability.
 * The messages module provides internationalization (i18n) catalog and message formatting.
 */
public final class MessagesModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("messages")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("messages", SemVer.of(2, 1, 0)))
            .build();
    }
}
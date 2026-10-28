package me.majhrs16.suite.messages;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;

/**
 * Runtime activator of the {@code messages} package.
 *
 * <p>Registers the locale-aware catalog in the module scope. The catalog keeps
 * no state outside its own classloader, so unloading the module releases it.</p>
 */
public final class MessagesActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("messages")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("messages", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        context.services().register(MessagesCatalog.class, MessagesCatalog.getInstance());
        context.logger().info("Messages catalog ready");
    }

    @Override
    public void stop() {
        // The module scope releases the service registration.
    }
}

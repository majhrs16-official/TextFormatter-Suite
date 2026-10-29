package me.majhrs16.suite.syncdiscord.jdk;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.discord.DiscordConnectionFactory;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;

/**
 * Publishes the JDK Discord transport for the neutral {@code sync-discord}
 * package.
 */
public final class SyncDiscordJdkActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-discord-jdk")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("discord-transport", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        context.services().register(
                DiscordConnectionFactory.class, new JdkDiscordConnectionFactory());
        context.logger().info("Discord JDK transport registered");
    }

    @Override
    public void stop() {
        // The service registration is closed by the module context scope.
    }
}

package me.majhrs16.suite.textformatter;

import java.nio.file.Files;
import java.nio.file.Path;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

/**
 * Runtime activator of the {@code textformatter} package.
 *
 * <p>Loads its channel catalog from its own configuration directory, composes
 * a {@link TextFormatter} with the translation and placeholder services
 * provided by other packages, and registers the result in the module scope.</p>
 */
public final class TextFormatterActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("textformatter")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("channel-api", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) throws Exception {
        Path channelsDir = context.configDir().resolve("channels");
        Files.createDirectories(channelsDir);

        ChannelConfig.LoadResult<ChannelRegistry> loaded = ChannelConfig.loadChannels(channelsDir);
        for (String error : loaded.errors()) {
            context.logger().warn(error);
        }

        TranslationService translation = context.services().find(TranslationService.class)
                .orElseGet(TextFormatters::emptyTranslation);
        PlaceholderResolver placeholders = context.services()
                .find(PlaceholderResolver.class)
                .orElse(null);

        TextFormatter formatter = TextFormatters.create(
                loaded.config(), translation, placeholders, context.logger());
        context.services().register(TextFormatter.class, formatter);
        context.services().register(ChannelRegistry.class, loaded.config());
        context.logger().info("TextFormatter ready with " + loaded.config().paths().size() + " channels");
    }

    @Override
    public void stop() {
        // The module scope releases services and resources.
    }
}

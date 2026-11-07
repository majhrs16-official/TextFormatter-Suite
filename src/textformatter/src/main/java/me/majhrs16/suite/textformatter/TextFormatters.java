package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.textformatter.template.TemplateRenderer;

import java.util.Objects;

/**
 * Wires the pieces of the TextFormatter module together.
 *
 * <p>Used by platform hosts and the iFlow module to obtain a fully assembled
 * instance from a channel catalog and platform-provided services. Translation
 * is not part of the formatter: it lives in the {@code translate} stage, which
 * runs before formatting.</p>
 */
public final class TextFormatters {

    private TextFormatters() {
    }

    /** A logger that does nothing (use platform logger in production). */
    public static PluginLogger noopLogger() {
        return new PluginLogger() {
            @Override public void info(String message, Object... args) {}
            @Override public void warn(String message, Object... args) {}
            @Override public void error(String message, Object... args) {}
            @Override public void error(String message, Throwable throwable) {}
            @Override public void debug(String message, Object... args) {}
        };
    }

    public static TextFormatter create(ChannelRegistry channels) {
        return create(channels, null, noopLogger());
    }

    public static TextFormatter create(ChannelRegistry channels,
                                       PlaceholderResolver placeholders,
                                       PluginLogger logger) {
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(logger, "logger");
        TemplateRenderer renderer = new TemplateRenderer(placeholders, logger);
        return new DefaultTextFormatter(channels, renderer);
    }
}

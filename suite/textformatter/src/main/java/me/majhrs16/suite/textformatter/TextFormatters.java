package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.textformatter.template.TemplateRenderer;

import java.util.Objects;

/**
 * Wires the pieces of the TextFormatter module together.
 *
 * <p>Used by platform hosts and the iFlow module to obtain a fully assembled
 * instance from a channel catalog and platform-provided services. When no
 * {@link TranslationService} is supplied, a null-object one is used so
 * {@code <tr>} spans stay untouched.</p>
 */
public final class TextFormatters {

    private TextFormatters() {
    }

    /** A translation service with a single fallback provider that never translates. */
    public static TranslationService emptyTranslation() {
        return new TranslationService(new TranslatorManager());
    }

    /** A logger that writes to stdout with a prefix. */
    public static PluginLogger stdoutLogger() {
        return new PluginLogger() {
            @Override public void info(String message, Object... args) {
                System.out.println("[TEXTFORMATTER] " + format(message, args));
            }

            @Override public void warn(String message, Object... args) {
                System.out.println("[TEXTFORMATTER][WARN] " + format(message, args));
            }

            @Override public void error(String message, Object... args) {
                System.err.println("[TEXTFORMATTER][ERROR] " + format(message, args));
            }

            @Override public void error(String message, Throwable throwable) {
                System.err.println("[TEXTFORMATTER][ERROR] " + message);
                throwable.printStackTrace();
            }

            @Override public void debug(String message, Object... args) {
                System.out.println("[TEXTFORMATTER][DEBUG] " + format(message, args));
            }

            private String format(String message, Object... args) {
                return args.length == 0 ? message : String.format(message, args);
            }
        };
    }

    public static TextFormatter create(ChannelRegistry channels) {
        return create(channels, emptyTranslation(), null, stdoutLogger());
    }

    public static TextFormatter create(ChannelRegistry channels,
                                       TranslationService translation,
                                       me.majhrs16.suite.api.spi.PlaceholderResolver placeholders,
                                       PluginLogger logger) {
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(translation, "translation");
        Objects.requireNonNull(logger, "logger");
        TemplateRenderer renderer = new TemplateRenderer(translation, placeholders, logger);
        return new DefaultTextFormatter(channels, renderer);
    }
}
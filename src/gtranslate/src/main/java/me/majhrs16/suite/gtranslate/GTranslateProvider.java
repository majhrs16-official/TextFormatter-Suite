package me.majhrs16.suite.gtranslate;

import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorProvider;
import me.majhrs16.suite.api.spi.TranslationException;
import me.majhrs16.suite.transport.HttpTransport;
import me.majhrs16.suite.transport.Transport;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Service Provider for Google Translate backend.
 * Discovered via {@link ServiceLoader} through {@code META-INF/services/...}.
 */
public final class GTranslateProvider implements TranslatorProvider {

    @Override
    public String name() {
        return "google";
    }

    @Override
    public Translator create(Map<String, Object> config) throws TranslationException {
        if (!validateConfig(config)) {
            return null;
        }

        Duration timeout = Duration.ofSeconds(10);
        if (config.containsKey("timeout")) {
            Object t = config.get("timeout");
            if (t instanceof Number) {
                timeout = Duration.ofSeconds(((Number) t).longValue());
            } else if (t instanceof String) {
                timeout = Duration.ofSeconds(Long.parseLong((String) t));
            }
        }

        Transport transport = new HttpTransport(timeout, true);
        return new GTranslate(transport);
    }

    @Override
    public boolean validateConfig(Map<String, Object> config) {
        // Google Translate free endpoint requires no API key
        // Only optional timeout parameter
        return true;
    }
}
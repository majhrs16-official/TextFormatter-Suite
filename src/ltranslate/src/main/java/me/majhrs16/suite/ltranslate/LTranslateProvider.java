package me.majhrs16.suite.ltranslate;

import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorProvider;
import me.majhrs16.suite.api.spi.TranslationException;
import me.majhrs16.suite.transport.HttpTransport;
import me.majhrs16.suite.transport.Transport;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Service Provider for LibreTranslate backend.
 * Discovered via {@link ServiceLoader} through {@code META-INF/services/...}.
 */
public final class LTranslateProvider implements TranslatorProvider {

    @Override
    public String name() {
        return "libre";
    }

    @Override
    public Translator create(Map<String, Object> config) throws TranslationException {
        if (!validateConfig(config)) {
            return null;
        }

        String baseUrl = Objects.toString(config.get("base-url"), "").trim();
        String apiKey = config.containsKey("api-key") ? Objects.toString(config.get("api-key"), "").trim() : null;
        
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
        
        if (apiKey != null && !apiKey.isBlank()) {
            return new LTranslate(baseUrl, apiKey, transport);
        }
        return new LTranslate(baseUrl, transport);
    }

    @Override
    public boolean validateConfig(Map<String, Object> config) {
        String baseUrl = Objects.toString(config.get("base-url"), "").trim();
        return !baseUrl.isBlank();
    }
}
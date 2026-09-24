package me.majhrs16.suite.api.spi;

import java.util.Map;
import java.util.ServiceLoader;

/**
 * Service Provider Interface for Translator backends.
 * <p>
 * Implementations should be registered via {@code META-INF/services/me.majhrs16.suite.api.spi.TranslatorProvider}
 * and discovered using {@link ServiceLoader}.
 * <p>
 * This decouples the host from concrete translator implementations, enabling
 * Clean Architecture compliance where host depends on abstractions (SPI)
 * rather than concrete adapters.
 */
public interface TranslatorProvider {

    /**
     * @return the stable provider name (e.g., "google", "libre")
     */
    String name();

    /**
     * Creates a Translator instance from configuration.
     *
     * @param config provider-specific configuration (e.g., base-url, api-key)
     * @return a new Translator instance, or null if config is invalid/incomplete
     * @throws TranslationException if the provider cannot be created
     */
    Translator create(Map<String, Object> config) throws TranslationException;

    /**
     * Validates if the configuration is sufficient for this provider.
     *
     * @param config provider-specific configuration
     * @return true if config is valid and complete enough to create a translator
     */
    boolean validateConfig(Map<String, Object> config);
}
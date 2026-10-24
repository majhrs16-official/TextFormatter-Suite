package me.majhrs16.suite.api.spi;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

/**
 * Tests de contrato para {@link TranslatorProvider}.
 * Validan la interfaz de fábrica de providers.
 */
@DisplayName("TranslatorProvider — contrato SPI")
class TranslatorProviderContractTest {

    @Test
    @DisplayName("create(config) devuelve Translator no null cuando config válida")
    void createReturnsNonNullTranslatorWhenConfigValid() {
        TranslatorProvider provider = new TranslatorProvider() {
            @Override public String name() { return "test"; }
            @Override public Translator create(Map<String, Object> config) {
                return new Translator() {
                    @Override public String name() { return "test"; }
                    @Override public String translate(String text, String from, String to) { return text; }
                    @Override public String detect(String text) { return "en"; }
                    @Override public boolean isAvailable() { return true; }
                };
            }
            @Override public boolean validateConfig(Map<String, Object> config) { return true; }
        };

        Translator translator = provider.create(Collections.emptyMap());

        Assertions.assertThat(translator).isNotNull();
        Assertions.assertThat(translator.name()).isEqualTo("test");
    }

    @Test
    @DisplayName("name() coincide con Translator creado")
    void nameMatchesCreatedTranslator() {
        TranslatorProvider provider = new TranslatorProvider() {
            @Override public String name() { return "my-provider"; }
            @Override public Translator create(Map<String, Object> config) {
                return new Translator() {
                    @Override public String name() { return "my-provider"; }
                    @Override public String translate(String text, String from, String to) { return text; }
                    @Override public String detect(String text) { return "en"; }
                    @Override public boolean isAvailable() { return true; }
                };
            }
            @Override public boolean validateConfig(Map<String, Object> config) { return true; }
        };

        Assertions.assertThat(provider.create(Collections.emptyMap()).name()).isEqualTo(provider.name());
    }

    @Test
    @DisplayName("validateConfig() valida configuración")
    void validateConfigValidatesConfig() {
        TranslatorProvider provider = new TranslatorProvider() {
            @Override public String name() { return "test"; }
            @Override public Translator create(Map<String, Object> config) { return null; }
            @Override public boolean validateConfig(Map<String, Object> config) {
                return config != null && config.containsKey("api-key");
            }
        };

        Assertions.assertThat(provider.validateConfig(Collections.emptyMap())).isFalse();
        Assertions.assertThat(provider.validateConfig(Map.of("api-key", "secret"))).isTrue();
    }
}
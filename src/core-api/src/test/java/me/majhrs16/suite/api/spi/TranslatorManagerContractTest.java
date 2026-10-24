package me.majhrs16.suite.api.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests de contrato para {@link TranslatorManager}.
 * Validan que el manager gestiona correctamente el ciclo de vida de providers.
 */
@DisplayName("TranslatorManager — contrato SPI")
class TranslatorManagerContractTest {

    @Test
    @DisplayName("Constructor por defecto crea manager vacío")
    void defaultConstructorCreatesEmptyManager() {
        TranslatorManager manager = new TranslatorManager();

        assertThat(manager.active()).isNotNull();
        assertThat(manager.active().name()).isEqualTo("none");
        assertThat(manager.active().isAvailable()).isFalse();
    }

    @Test
    @DisplayName("add() añade provider y active() devuelve el primero disponible")
    void addAddsProviderAndActiveReturnsFirstAvailable() {
        TranslatorManager manager = new TranslatorManager();
        
        Translator provider1 = new Translator() {
            @Override public String name() { return "provider1"; }
            @Override public String translate(String text, String from, String to) { return text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return false; }
        };
        
        Translator provider2 = new Translator() {
            @Override public String name() { return "provider2"; }
            @Override public String translate(String text, String from, String to) { return text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        };

        manager.add(provider1);
        // provider1 no está disponible, active() devuelve fallback "none"
        assertThat(manager.active().name()).isEqualTo("none");
        assertThat(manager.active().isAvailable()).isFalse();

        manager.add(provider2);
        // provider2 SÍ está disponible, active() devuelve provider2
        assertThat(manager.active().name()).isEqualTo("provider2");
        assertThat(manager.active().isAvailable()).isTrue();
    }

    @Test
    @DisplayName("providers() devuelve todos los providers registrados (inmutable)")
    void providersReturnsAllRegistered() {
        TranslatorManager manager = new TranslatorManager();
        
        Translator p1 = new Translator() {
            @Override public String name() { return "p1"; }
            @Override public String translate(String text, String from, String to) { return text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        };
        Translator p2 = new Translator() {
            @Override public String name() { return "p2"; }
            @Override public String translate(String text, String from, String to) { return text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return false; }
        };

        manager.add(p1);
        manager.add(p2);

        var all = manager.providers();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(Translator::name).containsExactlyInAnyOrder("p1", "p2");
        
        // Lista inmutable
        assertThatThrownBy(() -> all.add(p1)).isInstanceOf(UnsupportedOperationException.class);
    }
}
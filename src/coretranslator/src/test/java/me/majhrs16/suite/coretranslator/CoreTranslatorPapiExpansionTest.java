package me.majhrs16.suite.coretranslator;

import me.majhrs16.suite.api.spi.PluginLogger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoreTranslatorPapiExpansion — PlaceholderAPI adapter")
class CoreTranslatorPapiExpansionTest {

    @Test
    @DisplayName("publica el identificador cot y la versión base")
    void exposesIdentifierAndVersion() {
        CoreTranslatorPapiExpansion expansion =
            new CoreTranslatorPapiExpansion(placeholders());

        assertThat(expansion.getIdentifier()).isEqualTo("cot");
        assertThat(expansion.getAuthor()).isEqualTo("Majhrs16");
        assertThat(expansion.getVersion()).isEqualTo("1.0.0");
        assertThat(expansion.persist()).isTrue();
        assertThat(expansion.canRegister()).isTrue();
    }

    @Test
    @DisplayName("onRequest sin jugador resuelve contra el actor consola")
    void resolvesConsoleRequests() {
        CoreTranslatorPapiExpansion expansion =
            new CoreTranslatorPapiExpansion(placeholders());

        assertThat(expansion.onRequest(null, "translate;en;es;hello"))
            .isEqualTo("hola hello");
    }

    private static CoreTranslatorPlaceholders placeholders() {
        return new CoreTranslatorPlaceholders(
            (text, from, to) -> "hola " + text,
            null,
            null,
            null,
            null,
            new NoopLogger());
    }

    private static final class NoopLogger implements PluginLogger {

        @Override
        public void info(String message, Object... args) {
        }

        @Override
        public void warn(String message, Object... args) {
        }

        @Override
        public void error(String message, Object... args) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }

        @Override
        public void debug(String message, Object... args) {
        }
    }
}

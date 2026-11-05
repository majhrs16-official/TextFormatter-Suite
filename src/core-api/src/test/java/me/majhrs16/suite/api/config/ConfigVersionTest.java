package me.majhrs16.suite.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigVersionTest {

    private static final Path SOURCE = Path.of("config.yml");

    @Test
    void acceptsQuotedSchemaVersion() {
        assertThatCode(() -> ConfigVersion.require(
                Map.of(ConfigVersion.KEY, "1.0"), SOURCE))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsNumericSchemaVersionBecauseSnakeYamlParsesItAsDouble() {
        assertThatCode(() -> ConfigVersion.require(
                Map.of(ConfigVersion.KEY, 1.0d), SOURCE))
                .doesNotThrowAnyException();
        assertThatCode(() -> ConfigVersion.require(
                Map.of(ConfigVersion.KEY, 1.0f), SOURCE))
                .doesNotThrowAnyException();
    }

    @Test
    void missingVersionUsesTheExactContractMessage() {
        assertThatThrownBy(() -> ConfigVersion.require(Map.of(), SOURCE))
                .isInstanceOf(ConfigVersionException.class)
                .hasMessageContaining(ConfigVersion.MISSING_MESSAGE)
                .hasMessageContaining(SOURCE.toString())
                .satisfies(error -> assertThat(((ConfigVersionException) error).detail())
                        .isEqualTo(ConfigVersion.MISSING_MESSAGE));
    }

    @Test
    void nullRootFailsAsMissingVersion() {
        assertThatThrownBy(() -> ConfigVersion.require(null, SOURCE))
                .isInstanceOf(ConfigVersionException.class)
                .hasMessageContaining(ConfigVersion.MISSING_MESSAGE);
    }

    @Test
    void rejectsOlderNewerAndAbbreviatedVersions() {
        for (String value : new String[]{"0.9", "1", "1.1", "2.0"}) {
            assertThatThrownBy(() -> ConfigVersion.require(
                    Map.of(ConfigVersion.KEY, value), SOURCE))
                    .as(value)
                    .isInstanceOf(ConfigVersionException.class)
                    .hasMessageContaining("no soportada");
        }
    }
}

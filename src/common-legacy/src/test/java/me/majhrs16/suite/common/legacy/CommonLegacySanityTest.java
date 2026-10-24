package me.majhrs16.suite.common.legacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de cordura (sanity) para common-legacy.
 * Objetivo T1: asegurar que el módulo tiene test dir, deps y tests pasando.
 */
@DisplayName("common-legacy — sanity")
class CommonLegacySanityTest {

    @Test
    @DisplayName("Entorno de test JUnit 5 + AssertJ operativo")
    void sanity() {
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("AssertJ fluent assertions básicas")
    void assertjWorks() {
        assertThat("common-legacy")
            .isNotEmpty()
            .startsWith("common")
            .endsWith("legacy")
            .contains("-");
    }
}
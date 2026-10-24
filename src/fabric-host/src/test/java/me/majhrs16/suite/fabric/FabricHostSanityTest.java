package me.majhrs16.suite.fabric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de cordura (sanity) para fabric-host.
 * Objetivo T1: asegurar que el módulo tiene test dir, deps y tests pasando.
 */
@DisplayName("fabric-host — sanity")
class FabricHostSanityTest {

    @Test
    @DisplayName("Entorno de test JUnit 5 + AssertJ operativo")
    void sanity() {
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("AssertJ fluent assertions básicas")
    void assertjWorks() {
        assertThat("fabric-host")
            .isNotEmpty()
            .startsWith("fabric")
            .endsWith("host")
            .contains("-");
    }
}
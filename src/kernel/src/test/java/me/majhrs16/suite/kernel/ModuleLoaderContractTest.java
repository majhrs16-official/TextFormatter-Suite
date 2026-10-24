package me.majhrs16.suite.kernel;

import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests de contrato para {@link ModuleLoader}.
 * Validan que ServiceLoader descubre módulos correctamente.
 */
@DisplayName("ModuleLoader — contrato SPI")
class ModuleLoaderContractTest {

    @Test
    @DisplayName("discover() devuelve lista no null")
    void discoverReturnsNonNullList() {
        List<Module> modules = ModuleLoader.discover();

        Assertions.assertThat(modules).isNotNull();
    }

    @Test
    @DisplayName("Cada Module descubierto tiene descriptor no null")
    void eachModuleHasNonNullDescriptor() {
        List<Module> modules = ModuleLoader.discover();

        for (Module m : modules) {
            Assertions.assertThat(m.descriptor()).isNotNull();
            Assertions.assertThat(m.descriptor().name()).isNotNull().isNotEmpty();
            Assertions.assertThat(m.descriptor().version()).isNotNull();
        }
    }

    @Test
    @DisplayName("descriptor() devuelve ModuleDescriptor válido")
    void descriptorReturnsValidModuleDescriptor() {
        // Test via mock para no depender de classpath real
        Module module = new Module() {
            @Override
            public ModuleDescriptor descriptor() {
                return ModuleDescriptor.builder("test-module")
                    .version(SemVer.parse("1.0.0"))
                    .contractVersion(SemVer.parse("2.0.0"))
                    .build();
            }
        };

        ModuleDescriptor desc = module.descriptor();

        Assertions.assertThat(desc.name()).isEqualTo("test-module");
        Assertions.assertThat(desc.version().toString()).isEqualTo("1.0.0");
        Assertions.assertThat(desc.contractVersion().toString()).isEqualTo("2.0.0");
    }
}
package me.majhrs16.suite.chattranslator;

import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.gtranslate.GTranslateModule;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.kernel.ResolutionStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de capabilities de ChatTranslator (ADR-008).
 *
 * <p>La façade {@code chattranslator} debe activarse sin backends y los
 * proveedores {@code translator} deben requerirla explícitamente. Esto fija
 * la dirección del grafo y la opcionalidad de la traducción.</p>
 */
@DisplayName("ChatTranslatorModule — capabilities (ADR-008)")
class ChatTranslatorModuleTest {

    private static final Environment ENV =
        new Environment(SemVer.of(2, 1, 0), SemVer.of(21, 0, 0));

    @Test
    @DisplayName("la façade provee 'chattranslator' y no exige backends")
    void facadeProvidesChattranslatorWithoutRequiringBackends() {
        ModuleDescriptor descriptor = new ChatTranslatorModule().descriptor();

        assertThat(descriptor.provides())
            .extracting(capability -> capability.name())
            .containsExactly("chattranslator");
        assertThat(descriptor.requires()).isEmpty();
    }

    @Test
    @DisplayName("'chattranslator' activa aunque no haya ningún proveedor 'translator'")
    void facadeActivatesWithoutBackendProviders() {
        Module facade = new ChatTranslatorModule();

        ResolutionResult result = new ModuleGraph(ENV).resolve(List.of(facade));

        assertThat(result.allResolved()).isTrue();
        assertThat(result.of(facade)).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(result.resolved()).containsExactly(facade);
    }

    @Test
    @DisplayName("un backend 'translator' sin façade queda inactivo")
    void backendWithoutFacadeIsRejected() {
        Module backend = new GTranslateModule();

        ResolutionResult result = new ModuleGraph(ENV).resolve(List.of(backend));

        assertThat(result.of(backend)).isEqualTo(ResolutionStatus.UNSATISFIED_REQUIREMENT);
        assertThat(result.reasonOf(backend)).contains("chattranslator");
        assertThat(result.resolved()).isEmpty();
    }

    @Test
    @DisplayName("un backend 'translator' activa cuando la façade está presente")
    void backendActivatesWhenFacadeIsPresent() {
        Module facade = new ChatTranslatorModule();
        Module backend = new GTranslateModule();

        ResolutionResult result = new ModuleGraph(ENV).resolve(List.of(backend, facade));

        assertThat(result.allResolved()).isTrue();
        assertThat(result.of(facade)).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(result.of(backend)).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(result.resolved()).hasSize(2);
    }
}

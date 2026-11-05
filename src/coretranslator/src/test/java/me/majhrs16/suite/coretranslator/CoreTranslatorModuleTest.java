package me.majhrs16.suite.coretranslator;

import java.util.List;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.chattranslator.ChatTranslatorModule;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.kernel.ResolutionStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoreTranslatorModule — legacy capability contract")
class CoreTranslatorModuleTest {

    private static final Environment ENV =
        new Environment(SemVer.of(1, 0, 0), SemVer.of(21, 0, 0));

    @Test
    @DisplayName("declara versión/contrato 1.0.0 y la capability coretranslator")
    void declaresBaseContractAndCapability() {
        ModuleDescriptor descriptor = new CoreTranslatorModule().descriptor();

        assertThat(descriptor.name()).isEqualTo("coretranslator");
        assertThat(descriptor.version().toString()).isEqualTo("1.0.0");
        assertThat(descriptor.contractVersion().toString()).isEqualTo("1.0.0");
        assertThat(descriptor.jvmMin()).isEqualTo(21);
        assertThat(descriptor.provides())
            .extracting(Capability::name)
            .containsExactly("coretranslator");
        assertThat(descriptor.requires())
            .extracting(Requirement::name)
            .containsExactly("chattranslator");
    }

    @Test
    @DisplayName("el requisito >=1.0.0 acepta la versión actual de chattranslator")
    void acceptsCurrentChatTranslatorCapability() {
        ModuleDescriptor descriptor = new CoreTranslatorModule().descriptor();
        Requirement requirement = descriptor.requires().iterator().next();

        assertThat(requirement.satisfiedBy(SemVer.of(1, 0, 0))).isTrue();
        assertThat(requirement.satisfiedBy(SemVer.of(1, 0, 0))).isTrue();
        assertThat(requirement.satisfiedBy(SemVer.of(0, 9, 0))).isFalse();
    }

    @Test
    @DisplayName("resuelve junto a chattranslator y falla si la façade no está")
    void resolvesWithChatTranslatorAndFailsAlone() {
        ModuleGraph graph = new ModuleGraph(ENV);
        CoreTranslatorModule coreTranslator = new CoreTranslatorModule();

        ResolutionResult withFacade = graph.resolve(List.of(
            coreTranslator, new ChatTranslatorModule()));
        ResolutionResult alone = graph.resolve(List.of(coreTranslator));

        assertThat(withFacade.allResolved()).isTrue();
        assertThat(withFacade.of(coreTranslator)).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(alone.of(coreTranslator)).isEqualTo(ResolutionStatus.UNSATISFIED_REQUIREMENT);
    }
}

package me.majhrs16.suite.coretranslator;

import java.util.List;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
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
        assertThat(descriptor.requires()).isEmpty();
    }

    @Test
    @DisplayName("se activa sin la façade chattranslator")
    void resolvesWithoutChatTranslator() {
        CoreTranslatorModule coreTranslator = new CoreTranslatorModule();

        ResolutionResult alone = new ModuleGraph(ENV).resolve(List.of(coreTranslator));

        assertThat(alone.allResolved()).isTrue();
        assertThat(alone.of(coreTranslator)).isEqualTo(ResolutionStatus.RESOLVED);
    }
}

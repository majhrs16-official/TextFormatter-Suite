package me.majhrs16.suite.manager.spi;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests de contrato para {@link ManifestParser}.
 * Validan el parsing y serialización del formato de manifiesto.
 */
@DisplayName("ManifestParser — contrato SPI")
class ManifestParserContractTest {

    @Nested
    @DisplayName("Remote Index (packages.list)")
    class RemoteIndex {

        @Test
        @DisplayName("parseRemoteIndex() parsea entrada válida")
        void parsesValidEntry() throws ManifestParser.ParseException {
            String content = "textformatter\thttps://repo.example.com/textformatter-1.0.0.jar\t123456\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";

            List<ManifestParser.RemoteIndexEntry> entries = ManifestParser.parseRemoteIndex(content);

            Assertions.assertThat(entries).hasSize(1);
            ManifestParser.RemoteIndexEntry e = entries.get(0);
            Assertions.assertThat(e.name()).isEqualTo("textformatter");
            Assertions.assertThat(e.url()).isEqualTo("https://repo.example.com/textformatter-1.0.0.jar");
            Assertions.assertThat(e.size()).isEqualTo(123456);
            Assertions.assertThat(e.sha256()).isEqualTo("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
        }

        @Test
        @DisplayName("Ignora líneas vacías y comentarios")
        void ignoresEmptyLinesAndComments() throws ManifestParser.ParseException {
            String content = "# Comment\n\ntextformatter\thttps://repo.example.com/f.jar\t100\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\n\n# Another comment";

            List<ManifestParser.RemoteIndexEntry> entries = ManifestParser.parseRemoteIndex(content);

            Assertions.assertThat(entries).hasSize(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "name\turl",                                    // pocos campos
            "name\turl\tnotanumber\thash",                 // size inválido
            "name\turl\t-1\thash",                         // size negativo
            "name\turl\t100\tinvalidhash",                 // SHA256 inválido
            "name\turl\t100\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef123456789"  // SHA256 muy corto
        })
        @DisplayName("Rechaza entradas malformadas")
        void rejectsMalformedEntries(String content) {
            Assertions.assertThatThrownBy(() -> ManifestParser.parseRemoteIndex(content))
                .isInstanceOf(ManifestParser.ParseException.class);
        }
    }

    @Nested
    @DisplayName("DependencySpec")
    class DependencySpec {

        @Test
        @DisplayName("parse() sin constraint devuelve *")
        void parseWithoutConstraintReturnsWildcard() {
            ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse("textformatter");

            Assertions.assertThat(spec.name()).isEqualTo("textformatter");
            Assertions.assertThat(spec.constraint()).isEqualTo("*");
            Assertions.assertThat(spec.toString()).isEqualTo("textformatter");
        }

        @Test
        @DisplayName("parse() con @ devuelve constraint")
        void parseWithAtReturnsConstraint() {
            ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse("textformatter@>=1.0.0");

            Assertions.assertThat(spec.name()).isEqualTo("textformatter");
            Assertions.assertThat(spec.constraint()).isEqualTo(">=1.0.0");
            Assertions.assertThat(spec.toString()).isEqualTo("textformatter@>=1.0.0");
        }

        @Test
        @DisplayName("parse() con espacio devuelve constraint")
        void parseWithSpaceReturnsConstraint() {
            ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse("textformatter ^1.0.0");

            Assertions.assertThat(spec.name()).isEqualTo("textformatter");
            Assertions.assertThat(spec.constraint()).isEqualTo("^1.0.0");
        }

        @Test
        @DisplayName("parse() blank devuelve null, null lanza NPE")
        void parseBlankReturnsNullNullThrowsNPE() {
            Assertions.assertThat(ManifestParser.DependencySpec.parse("")).isNull();
            Assertions.assertThat(ManifestParser.DependencySpec.parse("  ")).isNull();
            Assertions.assertThatThrownBy(() -> ManifestParser.DependencySpec.parse((String)null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Manifest (multi-block)")
    class Manifest {

        private static String validBlock() {
            return "Name: test-package\n" +
                   "Version: 1.0.0\n" +
                   "Channel: stable\n" +
                   "Type: PACKAGE\n" +
                   "Dependencies: core-api\n" +
                   "Arch: none\n" +
                   "Platform: common\n" +
                   "URL: https://example.com/test.jar\n" +
                   "OS: none\n" +
                   "SHA256: abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\n" +
                   "Size: 1024\n" +
                   "Files: \n" +
                   "  test.jar\t1024\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\ttest-package\n";
        }

        @Test
        @DisplayName("parseManifest() parsea bloque válido")
        void parsesValidBlock() throws ManifestParser.ParseException {
            List<PackageEntry> entries = ManifestParser.parseManifest(validBlock());

            Assertions.assertThat(entries).hasSize(1);
            PackageEntry e = entries.get(0);
            Assertions.assertThat(e.name()).isEqualTo("test-package");
            Assertions.assertThat(e.version()).isEqualTo("1.0.0");
            Assertions.assertThat(e.channel()).isEqualTo("stable");
            Assertions.assertThat(e.type()).isEqualTo(PackageType.PACKAGE);
            Assertions.assertThat(e.dependencies()).containsExactly("core-api");
            Assertions.assertThat(e.files()).hasSize(1);
        }

        @Test
        @DisplayName("parseManifest() parsea múltiples bloques")
        void parsesMultipleBlocks() throws ManifestParser.ParseException {
            String content = validBlock() + "\n\n" + validBlock().replace("test-package", "other-package");

            List<PackageEntry> entries = ManifestParser.parseManifest(content);

            Assertions.assertThat(entries).hasSize(2);
            Assertions.assertThat(entries.get(0).name()).isEqualTo("test-package");
            Assertions.assertThat(entries.get(1).name()).isEqualTo("other-package");
        }

        @Test
        @DisplayName("serialize() round-trip produce equivalente")
        void serializeRoundTrip() throws ManifestParser.ParseException {
            List<PackageEntry> original = ManifestParser.parseManifest(validBlock());
            String serialized = ManifestParser.serialize(original);
            List<PackageEntry> parsed = ManifestParser.parseManifest(serialized);

            Assertions.assertThat(parsed).hasSize(1);
            Assertions.assertThat(parsed.get(0).name()).isEqualTo(original.get(0).name());
            Assertions.assertThat(parsed.get(0).version()).isEqualTo(original.get(0).version());
            Assertions.assertThat(parsed.get(0).files()).hasSize(original.get(0).files().size());
        }

        @Test
        @DisplayName("Rechaza bloque sin campos obligatorios (Name, Version, Channel, Type)")
        void rejectsMissingRequiredFields() {
            // Falta Name
            String badBlock1 = "Version: 1.0.0\nChannel: stable\nType: PACKAGE\n\n\n";
            Assertions.assertThatThrownBy(() -> ManifestParser.parseManifest(badBlock1))
                .isInstanceOf(ManifestParser.ParseException.class)
                .hasMessageContaining("missing required field");

            // Falta Version
            String badBlock2 = "Name: test\nChannel: stable\nType: PACKAGE\n\n\n";
            Assertions.assertThatThrownBy(() -> ManifestParser.parseManifest(badBlock2))
                .isInstanceOf(ManifestParser.ParseException.class)
                .hasMessageContaining("missing required field");

            // Falta Channel
            String badBlock3 = "Name: test\nVersion: 1.0.0\nType: PACKAGE\n\n\n";
            Assertions.assertThatThrownBy(() -> ManifestParser.parseManifest(badBlock3))
                .isInstanceOf(ManifestParser.ParseException.class)
                .hasMessageContaining("missing required field");

            // Falta Type
            String badBlock4 = "Name: test\nVersion: 1.0.0\nChannel: stable\n\n\n";
            Assertions.assertThatThrownBy(() -> ManifestParser.parseManifest(badBlock4))
                .isInstanceOf(ManifestParser.ParseException.class)
                .hasMessageContaining("missing required field");
        }

        @Test
        @DisplayName("Rechaza Type inválido")
        void rejectsInvalidType() {
            String badBlock = validBlock().replace("Type: PACKAGE", "Type: INVALID");

            Assertions.assertThatThrownBy(() -> ManifestParser.parseManifest(badBlock))
                .isInstanceOf(ManifestParser.ParseException.class)
                .hasMessageContaining("invalid Type");
        }

        @Test
        @DisplayName("Rechaza SHA256 inválido en campo")
        void rejectsInvalidSha256InField() {
            String badBlock = validBlock().replace(
                "SHA256: abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                "SHA256: invalid"
            );

            Assertions.assertThatThrownBy(() -> ManifestParser.parseManifest(badBlock))
                .isInstanceOf(ManifestParser.ParseException.class)
                .hasMessageContaining("invalid SHA256");
        }
    }
}
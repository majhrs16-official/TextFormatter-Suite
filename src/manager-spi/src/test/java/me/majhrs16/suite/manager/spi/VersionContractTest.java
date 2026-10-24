package me.majhrs16.suite.manager.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests de contrato para {@link Version}.
 * Validan que el modelo inmutable cumple semver 2.0.0.
 */
@DisplayName("Version — contrato SPI")
class VersionContractTest {

    @Nested
    @DisplayName("Parsing")
    class Parsing {

        @Test
        @DisplayName("parse() acepta versiones válidas")
        void parseAcceptsValidVersions() {
            assertThatCode(() -> Version.parse("1.0.0")).doesNotThrowAnyException();
            assertThatCode(() -> Version.parse("0.0.1")).doesNotThrowAnyException();
            assertThatCode(() -> Version.parse("10.20.30")).doesNotThrowAnyException();
            assertThatCode(() -> Version.parse("1.0.0-alpha")).doesNotThrowAnyException();
            assertThatCode(() -> Version.parse("1.0.0-alpha.1")).doesNotThrowAnyException();
            assertThatCode(() -> Version.parse("1.0.0-0.3.7")).doesNotThrowAnyException();
            assertThatCode(() -> Version.parse("1.0.0-x.7.z.92")).doesNotThrowAnyException();
            assertThatCode(() -> Version.parse("1.0.0+build")).doesNotThrowAnyException();
            assertThatCode(() -> Version.parse("1.0.0-alpha+build")).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "1.0", "v1.0.0", "1.0.0.", "1..0.0", "a.b.c"})
        @DisplayName("parse() rechaza versiones inválidas")
        void parseRejectsInvalidVersions(String invalid) {
            assertThatThrownBy(() -> Version.parse(invalid))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("tryParse() devuelve null en lugar de lanzar excepción")
        void tryParseReturnsNullOnInvalid() {
            assertThat(Version.tryParse("invalid")).isNull();
            assertThat(Version.tryParse("")).isNull();
            assertThat(Version.tryParse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("Componentes (inmutabilidad)")
    class Components {

        @Test
        @DisplayName("Componentes accesibles y correctos")
        void componentsAccessibleAndCorrect() {
            Version v = Version.parse("2.3.4-alpha.1+build.5");

            assertThat(v.major()).isEqualTo(2);
            assertThat(v.minor()).isEqualTo(3);
            assertThat(v.patch()).isEqualTo(4);
            assertThat(v.prerelease()).isEqualTo("alpha.1");
            assertThat(v.build()).isEqualTo("build.5");
            assertThat(v.original()).isEqualTo("2.3.4-alpha.1+build.5");
        }

        @Test
        @DisplayName("Versión sin prerelease/build tiene null en esos campos")
        void noPrereleaseOrBuildHasNull() {
            Version v = Version.parse("1.0.0");

            assertThat(v.prerelease()).isNull();
            assertThat(v.build()).isNull();
        }
    }

    @Nested
    @DisplayName("Comparación (semver 2.0.0)")
    class Comparison {

        @Test
        @DisplayName("Major > Minor > Patch")
        void majorMinorPatchOrder() {
            assertThat(Version.parse("2.0.0")).isGreaterThan(Version.parse("1.9.9"));
            assertThat(Version.parse("1.2.0")).isGreaterThan(Version.parse("1.1.9"));
            assertThat(Version.parse("1.1.2")).isGreaterThan(Version.parse("1.1.1"));
        }

        @Test
        @DisplayName("Release > Prerelease")
        void releaseGreaterThanPrerelease() {
            assertThat(Version.parse("1.0.0")).isGreaterThan(Version.parse("1.0.0-alpha"));
            assertThat(Version.parse("1.0.0")).isGreaterThan(Version.parse("1.0.0-beta"));
            assertThat(Version.parse("1.0.0-rc.1")).isGreaterThan(Version.parse("1.0.0-beta.2"));
        }

        @Test
        @DisplayName("Prerelease: numérico < alfanumérico")
        void numericPrereleaseLessThanAlphanumeric() {
            assertThat(Version.parse("1.0.0-1")).isLessThan(Version.parse("1.0.0-alpha"));
        }

        @Test
        @DisplayName("Prerelease: más identificadores > menos (si prefijo igual)")
        void moreIdentifiersGreaterWhenPrefixEqual() {
            assertThat(Version.parse("1.0.0-alpha.1")).isGreaterThan(Version.parse("1.0.0-alpha"));
        }

        @Test
        @DisplayName("Build metadata NO afecta comparación")
        void buildMetadataIgnoredInComparison() {
            Version v1 = Version.parse("1.0.0+build1");
            Version v2 = Version.parse("1.0.0+build2");

            assertThat(v1.compareTo(v2)).isZero();
        }
    }

    @Nested
    @DisplayName("Rangos (satisfies)")
    class Ranges {

        @Test
        @DisplayName("Exact match")
        void exactMatch() {
            assertThat(Version.parse("1.2.3").satisfies("1.2.3")).isTrue();
            assertThat(Version.parse("1.2.3").satisfies("1.2.4")).isFalse();
        }

        @Test
        @DisplayName("Caret ^ (compatible)")
        void caretRange() {
            // ^1.2.3 -> >=1.2.3 <2.0.0
            assertThat(Version.parse("1.2.3").satisfies("^1.2.3")).isTrue();
            assertThat(Version.parse("1.2.9").satisfies("^1.2.3")).isTrue();
            assertThat(Version.parse("1.3.0").satisfies("^1.2.3")).isTrue();
            assertThat(Version.parse("2.0.0").satisfies("^1.2.3")).isFalse();
            assertThat(Version.parse("0.2.3").satisfies("^1.2.3")).isFalse();

            // ^0.2.3 -> >=0.2.3 <0.3.0
            assertThat(Version.parse("0.2.3").satisfies("^0.2.3")).isTrue();
            assertThat(Version.parse("0.2.9").satisfies("^0.2.3")).isTrue();
            assertThat(Version.parse("0.3.0").satisfies("^0.2.3")).isFalse();

            // ^0.0.3 -> >=0.0.3 <0.0.4
            assertThat(Version.parse("0.0.3").satisfies("^0.0.3")).isTrue();
            assertThat(Version.parse("0.0.4").satisfies("^0.0.3")).isFalse();
        }

        @Test
        @DisplayName("Tilde ~ (patch/minor)")
        void tildeRange() {
            // ~1.2.3 -> >=1.2.3 <1.3.0
            assertThat(Version.parse("1.2.3").satisfies("~1.2.3")).isTrue();
            assertThat(Version.parse("1.2.9").satisfies("~1.2.3")).isTrue();
            assertThat(Version.parse("1.3.0").satisfies("~1.2.3")).isFalse();

            // ~0.2.3 -> >=0.2.3 <0.3.0
            assertThat(Version.parse("0.2.3").satisfies("~0.2.3")).isTrue();
            assertThat(Version.parse("0.3.0").satisfies("~0.2.3")).isFalse();
        }

        @Test
        @DisplayName("Operadores >= <= > < !=")
        void operators() {
            assertThat(Version.parse("1.2.3").satisfies(">=1.2.0")).isTrue();
            assertThat(Version.parse("1.2.3").satisfies("<=1.2.3")).isTrue();
            assertThat(Version.parse("1.2.3").satisfies(">1.2.2")).isTrue();
            assertThat(Version.parse("1.2.3").satisfies("<1.2.4")).isTrue();
            assertThat(Version.parse("1.2.3").satisfies("!=1.2.4")).isTrue();
            assertThat(Version.parse("1.2.3").satisfies("!=1.2.3")).isFalse();
        }

        @Test
        @DisplayName("Rango con guión")
        void hyphenRange() {
            assertThat(Version.parse("1.2.3").satisfies("1.0.0 - 2.0.0")).isTrue();
            assertThat(Version.parse("0.9.0").satisfies("1.0.0 - 2.0.0")).isFalse();
            assertThat(Version.parse("2.0.0").satisfies("1.0.0 - 2.0.0")).isTrue();
            assertThat(Version.parse("2.0.1").satisfies("1.0.0 - 2.0.0")).isFalse();
        }

        @Test
        @DisplayName("OR (||)")
        void orRange() {
            assertThat(Version.parse("1.2.3").satisfies("1.2.3 || 2.0.0")).isTrue();
            assertThat(Version.parse("2.0.0").satisfies("1.2.3 || 2.0.0")).isTrue();
            assertThat(Version.parse("1.5.0").satisfies("1.2.3 || 2.0.0")).isFalse();
        }

        @Test
        @DisplayName("Wildcards")
        void wildcards() {
            assertThat(Version.parse("1.2.3").satisfies("1.2.*")).isTrue();
            assertThat(Version.parse("1.2.9").satisfies("1.2.*")).isTrue();
            assertThat(Version.parse("1.3.0").satisfies("1.2.*")).isFalse();
            assertThat(Version.parse("1.2.3").satisfies("1.*.*")).isTrue();
            assertThat(Version.parse("1.2.3").satisfies("1.x")).isTrue();
            assertThat(Version.parse("2.0.0").satisfies("1.x")).isFalse();
            assertThat(Version.parse("1.2.3").satisfies("*")).isTrue();
        }
    }

    @Nested
    @DisplayName("Equals / HashCode / toString")
    class Equality {

        @Test
        @DisplayName("Igualdad ignora build metadata")
        void equalityIgnoresBuild() {
            Version v1 = Version.parse("1.0.0+build1");
            Version v2 = Version.parse("1.0.0+build2");

            assertThat(v1).isEqualTo(v2);
            assertThat(v1.hashCode()).isEqualTo(v2.hashCode());
        }

        @Test
        @DisplayName("toString() devuelve original")
        void toStringReturnsOriginal() {
            assertThat(Version.parse("1.0.0-alpha+build").toString()).isEqualTo("1.0.0-alpha+build");
        }
    }
}
package me.majhrs16.suite.extension;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.SemVer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests de contrato para {@link Extension}.
 * Validan que la interfaz cumple su contrato sin depender de implementaciones concretas.
 */
@DisplayName("Extension — contrato SPI")
class ExtensionContractTest {

    @Test
    @DisplayName("id() no es null ni vacío y sigue kebab-case")
    void idIsNotNullNorEmptyAndKebabCase() {
        Extension ext = new Extension() {
            @Override public String id() { return "my-extension"; }
            @Override public String name() { return "Test"; }
            @Override public SemVer version() { return SemVer.parse("1.0.0"); }
            @Override public SemVer requiredCoreApi() { return SemVer.parse("2.0.0"); }
            @Override public List<String> dependencies() { return List.of(); }
            @Override public Set<Capability> providedCapabilities() { return Set.of(); }
            @Override public Set<Capability> requiredCapabilities() { return Set.of(); }
            @Override public void onEnable(ExtensionContext context) {}
            @Override public void onDisable() {}
            @Override public ExtensionMetadata metadata() { 
                return ExtensionMetadata.builder()
                    .id("test")
                    .name("Test")
                    .minSuiteVersion("2.1.0")
                    .build();
            }
        };

        String id = ext.id();

        Assertions.assertThat(id).isNotNull().isNotEmpty();
        Assertions.assertThat(id).matches("^[a-z0-9]+(-[a-z0-9]+)*$"); // kebab-case
    }

    @Test
    @DisplayName("name() no es null ni vacío")
    void nameIsNotNullNorEmpty() {
        Extension ext = new Extension() {
            @Override public String id() { return "test"; }
            @Override public String name() { return "Test Extension"; }
            @Override public SemVer version() { return SemVer.parse("1.0.0"); }
            @Override public SemVer requiredCoreApi() { return SemVer.parse("2.0.0"); }
            @Override public List<String> dependencies() { return List.of(); }
            @Override public Set<Capability> providedCapabilities() { return Set.of(); }
            @Override public Set<Capability> requiredCapabilities() { return Set.of(); }
            @Override public void onEnable(ExtensionContext context) {}
            @Override public void onDisable() {}
            @Override public ExtensionMetadata metadata() { 
                return ExtensionMetadata.builder()
                    .id("test")
                    .name("Test")
                    .minSuiteVersion("2.1.0")
                    .build();
            }
        };

        Assertions.assertThat(ext.name()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("version() y requiredCoreApi() son SemVer válidos")
    void versionsAreValidSemVer() {
        Extension ext = new Extension() {
            @Override public String id() { return "test"; }
            @Override public String name() { return "Test"; }
            @Override public SemVer version() { return SemVer.parse("1.2.3"); }
            @Override public SemVer requiredCoreApi() { return SemVer.parse("2.0.0"); }
            @Override public List<String> dependencies() { return List.of(); }
            @Override public Set<Capability> providedCapabilities() { return Set.of(); }
            @Override public Set<Capability> requiredCapabilities() { return Set.of(); }
            @Override public void onEnable(ExtensionContext context) {}
            @Override public void onDisable() {}
            @Override public ExtensionMetadata metadata() { 
                return ExtensionMetadata.builder()
                    .id("test")
                    .name("Test")
                    .minSuiteVersion("2.1.0")
                    .build();
            }
        };

        Assertions.assertThat(ext.version().toString()).isEqualTo("1.2.3");
        Assertions.assertThat(ext.requiredCoreApi().toString()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("dependencies() devuelve lista inmutable/segura")
    void dependenciesReturnsSafeList() {
        Extension ext = new Extension() {
            @Override public String id() { return "test"; }
            @Override public String name() { return "Test"; }
            @Override public SemVer version() { return SemVer.parse("1.0.0"); }
            @Override public SemVer requiredCoreApi() { return SemVer.parse("2.0.0"); }
            @Override public List<String> dependencies() { return List.of("dep1", "dep2"); }
            @Override public Set<Capability> providedCapabilities() { return Set.of(); }
            @Override public Set<Capability> requiredCapabilities() { return Set.of(); }
            @Override public void onEnable(ExtensionContext context) {}
            @Override public void onDisable() {}
            @Override public ExtensionMetadata metadata() { 
                return ExtensionMetadata.builder()
                    .id("test")
                    .name("Test")
                    .minSuiteVersion("2.1.0")
                    .build();
            }
        };

        List<String> deps = ext.dependencies();

        Assertions.assertThat(deps).containsExactly("dep1", "dep2");
        // Verificar que la lista devuelta no permite modificación externa
        Assertions.assertThatThrownBy(() -> deps.add("hack")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("providedCapabilities() y requiredCapabilities() devuelven Sets")
    void capabilitiesReturnSets() {
        Capability cap1 = Capability.of("custom-channel", SemVer.parse("1.0.0"));
        Capability cap2 = Capability.of("translation-provider", SemVer.parse("1.0.0"));

        Extension ext = new Extension() {
            @Override public String id() { return "test"; }
            @Override public String name() { return "Test"; }
            @Override public SemVer version() { return SemVer.parse("1.0.0"); }
            @Override public SemVer requiredCoreApi() { return SemVer.parse("2.0.0"); }
            @Override public List<String> dependencies() { return List.of(); }
            @Override public Set<Capability> providedCapabilities() { return Set.of(cap1); }
            @Override public Set<Capability> requiredCapabilities() { return Set.of(cap2); }
            @Override public void onEnable(ExtensionContext context) {}
            @Override public void onDisable() {}
            @Override public ExtensionMetadata metadata() { 
                return ExtensionMetadata.builder()
                    .id("test")
                    .name("Test")
                    .minSuiteVersion("2.1.0")
                    .build();
            }
        };

        Assertions.assertThat(ext.providedCapabilities()).containsExactly(cap1);
        Assertions.assertThat(ext.requiredCapabilities()).containsExactly(cap2);
    }

    @Test
    @DisplayName("onEnable/onDisable/onConfigReload no lanzan excepción por defecto")
    void lifecycleMethodsDontThrow() {
        Extension ext = new Extension() {
            @Override public String id() { return "test"; }
            @Override public String name() { return "Test"; }
            @Override public SemVer version() { return SemVer.parse("1.0.0"); }
            @Override public SemVer requiredCoreApi() { return SemVer.parse("2.0.0"); }
            @Override public List<String> dependencies() { return List.of(); }
            @Override public Set<Capability> providedCapabilities() { return Set.of(); }
            @Override public Set<Capability> requiredCapabilities() { return Set.of(); }
            @Override public void onEnable(ExtensionContext context) {}
            @Override public void onDisable() {}
            @Override public ExtensionMetadata metadata() { 
                return ExtensionMetadata.builder()
                    .id("test")
                    .name("Test")
                    .minSuiteVersion("2.1.0")
                    .build();
            }
        };

        Assertions.assertThatCode(ext::onDisable).doesNotThrowAnyException();
        Assertions.assertThatCode(() -> ext.onConfigReload(ExtensionConfig.empty())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("metadata() devuelve ExtensionMetadata válida")
    void metadataReturnsValidMetadata() {
        Extension ext = new Extension() {
            @Override public String id() { return "test"; }
            @Override public String name() { return "Test"; }
            @Override public SemVer version() { return SemVer.parse("1.0.0"); }
            @Override public SemVer requiredCoreApi() { return SemVer.parse("2.0.0"); }
            @Override public List<String> dependencies() { return List.of(); }
            @Override public Set<Capability> providedCapabilities() { return Set.of(); }
            @Override public Set<Capability> requiredCapabilities() { return Set.of(); }
            @Override public void onEnable(ExtensionContext context) {}
            @Override public void onDisable() {}
            @Override public ExtensionMetadata metadata() { 
                return ExtensionMetadata.builder()
                    .id("test")
                    .name("Test Extension")
                    .description("Description")
                    .author("Author")
                    .minSuiteVersion("2.1.0")
                    .build(); 
            }
        };

        ExtensionMetadata meta = ext.metadata();

        Assertions.assertThat(meta.id()).isEqualTo("test");
        Assertions.assertThat(meta.name()).isEqualTo("Test Extension");
        Assertions.assertThat(meta.description()).isEqualTo("Description");
        Assertions.assertThat(meta.author()).isEqualTo("Author");
    }

    @Test
    @DisplayName("configSchema() por defecto es JSON Schema vacío válido")
    void configSchemaDefaultsToEmptyObject() {
        Extension ext = new Extension() {
            @Override public String id() { return "test"; }
            @Override public String name() { return "Test"; }
            @Override public SemVer version() { return SemVer.parse("1.0.0"); }
            @Override public SemVer requiredCoreApi() { return SemVer.parse("2.0.0"); }
            @Override public List<String> dependencies() { return List.of(); }
            @Override public Set<Capability> providedCapabilities() { return Set.of(); }
            @Override public Set<Capability> requiredCapabilities() { return Set.of(); }
            @Override public void onEnable(ExtensionContext context) {}
            @Override public void onDisable() {}
            @Override public ExtensionMetadata metadata() { 
                return ExtensionMetadata.builder()
                    .id("test")
                    .name("Test")
                    .minSuiteVersion("2.1.0")
                    .build();
            }
        };

        Assertions.assertThat(ext.configSchema()).isEqualTo("{}");
    }
}
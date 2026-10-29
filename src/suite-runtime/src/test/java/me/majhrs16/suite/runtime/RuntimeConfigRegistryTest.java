package me.majhrs16.suite.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.majhrs16.suite.api.config.ConfigConflictException;
import me.majhrs16.suite.api.config.ConfigRegistration;
import me.majhrs16.suite.api.config.ConfigSection;
import me.majhrs16.suite.api.config.ConfigValue;
import org.junit.jupiter.api.Test;

/** F4d gate: lifecycle-bound configuration sections. */
class RuntimeConfigRegistryTest {

    @Test
    void registersFindsAndRemovesSections() {
        RuntimeConfigRegistry registry = new RuntimeConfigRegistry();
        TestSection section = section("demo", "greeting");

        ConfigRegistration registration = registry.register("demo", section);

        assertTrue(registry.find("demo").isPresent());
        assertEquals(List.of(section), registry.sections());

        section.reload();
        assertEquals(1, section.reloads);

        registration.close();
        registration.close();

        assertTrue(registry.find("demo").isEmpty());
        assertTrue(registry.sections().isEmpty());
    }

    @Test
    void rejectsDuplicateNamespaces() {
        RuntimeConfigRegistry registry = new RuntimeConfigRegistry();
        registry.register("demo", section("demo", "a"));

        assertThrows(ConfigConflictException.class,
                () -> registry.register("other", section("demo", "b")));
    }

    @Test
    void moduleScopeRejectsSectionsOutsideItsNamespace() {
        RuntimeConfigRegistry registry = new RuntimeConfigRegistry();

        assertThrows(IllegalArgumentException.class,
                () -> registry.scope("demo").register(section("other", "a")));
    }

    @Test
    void defaultValidationChecksDeclaredTypes() {
        ConfigSection section = section("demo", "count");
        ConfigSection typed = new ConfigSection() {
            @Override
            public String namespace() {
                return "typed";
            }

            @Override
            public List<ConfigValue> values() {
                return List.of(ConfigValue.of("count", Integer.class, 1));
            }

            @Override
            public void reload() {
                throw new UnsupportedOperationException("not reloadable");
            }
        };

        typed.validate(java.util.Map.of("count", 2));

        assertThrows(IllegalArgumentException.class,
                () -> typed.validate(java.util.Map.of("count", "two")));
    }

    @Test
    void removesEverySectionOwnedByAModule() {
        RuntimeConfigRegistry registry = new RuntimeConfigRegistry();
        registry.register("demo", section("demo", "a"));
        registry.register("runtime", section("runtime", "b"));

        registry.removeOwner("demo");

        assertTrue(registry.find("demo").isEmpty());
        assertTrue(registry.find("runtime").isPresent());
        assertFalse(registry.sections().isEmpty());
    }

    private static TestSection section(String namespace, String key) {
        return new TestSection(namespace, key);
    }

    private static final class TestSection implements ConfigSection {

        private final String namespace;
        private final String key;
        private int reloads;

        private TestSection(String namespace, String key) {
            this.namespace = namespace;
            this.key = key;
        }

        @Override
        public String namespace() {
            return namespace;
        }

        @Override
        public List<ConfigValue> values() {
            return List.of(ConfigValue.of(key, String.class, "value"));
        }

        @Override
        public void reload() {
            reloads++;
        }
    }
}

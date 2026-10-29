package me.majhrs16.suite.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import me.majhrs16.suite.api.config.ConfigConflictException;
import me.majhrs16.suite.api.config.ConfigRegistration;
import me.majhrs16.suite.api.config.ConfigRegistry;
import me.majhrs16.suite.api.config.ConfigSection;

/**
 * Runtime implementation of the neutral configuration registry.
 *
 * <p>Every section records its owner. Closing the registration or deactivating
 * the owning module removes the live section; persisted values are untouched.</p>
 */
public final class RuntimeConfigRegistry implements ConfigRegistry {

    private final Map<String, Registered> sections = new LinkedHashMap<>();

    @Override
    public ConfigRegistration register(ConfigSection section) {
        return register("runtime", section);
    }

    /**
     * Registers a section on behalf of {@code owner}.
     *
     * @param owner module id or {@code runtime}
     * @param section module-owned section
     * @return a handle that removes the section
     */
    public ConfigRegistration register(String owner, ConfigSection section) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(section, "section");
        String namespace = section.namespace();
        if (namespace == null || !namespace.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalArgumentException("config namespace is invalid: " + namespace);
        }
        if (sections.containsKey(namespace)) {
            throw new ConfigConflictException("config namespace is already registered: " + namespace);
        }
        sections.put(namespace, new Registered(owner, section));
        return () -> remove(owner, namespace);
    }

    /**
     * @param owner module id
     * @return the module-scoped view of this registry
     */
    public ConfigRegistry scope(String owner) {
        return new ScopedRegistry(owner);
    }

    /**
     * Removes every section owned by {@code owner}.
     *
     * @param owner module id
     */
    public void removeOwner(String owner) {
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, Registered> entry : sections.entrySet()) {
            if (entry.getValue().owner.equals(owner)) {
                removed.add(entry.getKey());
            }
        }
        removed.forEach(sections::remove);
    }

    @Override
    public Optional<ConfigSection> find(String namespace) {
        Registered registered = sections.get(namespace);
        return registered == null ? Optional.empty() : Optional.of(registered.section);
    }

    @Override
    public List<ConfigSection> sections() {
        return sections.values().stream().map(Registered::section).toList();
    }

    private void remove(String owner, String namespace) {
        Registered registered = sections.get(namespace);
        if (registered != null && registered.owner.equals(owner)) {
            sections.remove(namespace);
        }
    }

    private record Registered(String owner, ConfigSection section) { }

    private final class ScopedRegistry implements ConfigRegistry {

        private final String owner;

        private ScopedRegistry(String owner) {
            this.owner = owner;
        }

        @Override
        public ConfigRegistration register(ConfigSection section) {
            if (!section.namespace().equals(owner)) {
                throw new IllegalArgumentException(
                        "module config namespace must equal its module id: " + owner);
            }
            return RuntimeConfigRegistry.this.register(owner, section);
        }

        @Override
        public Optional<ConfigSection> find(String namespace) {
            return RuntimeConfigRegistry.this.find(namespace);
        }

        @Override
        public List<ConfigSection> sections() {
            return RuntimeConfigRegistry.this.sections();
        }
    }
}

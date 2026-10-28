package me.majhrs16.suite.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import me.majhrs16.suite.api.runtime.ServiceRegistry;

/**
 * Registry of live services shared by every active module.
 *
 * <p>Every registration records its owner so deactivating a module removes
 * exactly what that module registered and nothing else. {@link #scope(String)}
 * returns the view handed to a module through its {@code ModuleContext}.</p>
 */
public final class RuntimeServiceRegistry implements ServiceRegistry {

    private final Map<Class<?>, List<Entry>> services = new LinkedHashMap<>();

    @Override
    public <T> Registration register(Class<T> type, T service) {
        return register("runtime", type, service);
    }

    /**
     * Registers a service on behalf of {@code owner}.
     *
     * @param owner module id that owns the registration
     * @param type  contract type
     * @param service implementation
     * @param <T>   service type
     * @return registration handle
     */
    public <T> Registration register(String owner, Class<T> type, T service) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
        Entry entry = new Entry(owner, service);
        services.computeIfAbsent(type, ignored -> new ArrayList<>()).add(entry);
        return () -> remove(type, entry);
    }

    @Override
    public <T> Optional<T> find(Class<T> type) {
        List<Entry> entries = services.get(type);
        return entries == null || entries.isEmpty()
                ? Optional.empty()
                : Optional.of(type.cast(entries.get(0).service));
    }

    @Override
    public <T> List<T> findAll(Class<T> type) {
        List<Entry> entries = services.get(type);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<T> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            result.add(type.cast(entry.service));
        }
        return List.copyOf(result);
    }

    /**
     * Removes every registration owned by {@code owner}.
     *
     * @param owner module id
     */
    public void removeOwner(String owner) {
        List<Class<?>> emptyTypes = new ArrayList<>();
        for (Map.Entry<Class<?>, List<Entry>> e : services.entrySet()) {
            e.getValue().removeIf(entry -> entry.owner.equals(owner));
            if (e.getValue().isEmpty()) {
                emptyTypes.add(e.getKey());
            }
        }
        emptyTypes.forEach(services::remove);
    }

    /**
     * @param owner module id
     * @return the module-scoped view of this registry
     */
    public ServiceRegistry scope(String owner) {
        return new ScopedRegistry(owner);
    }

    private <T> void remove(Class<T> type, Entry entry) {
        List<Entry> entries = services.get(type);
        if (entries != null) {
            entries.remove(entry);
            if (entries.isEmpty()) {
                services.remove(type);
            }
        }
    }

    private record Entry(String owner, Object service) { }

    private final class ScopedRegistry implements ServiceRegistry {

        private final String owner;

        private ScopedRegistry(String owner) {
            this.owner = owner;
        }

        @Override
        public <T> Registration register(Class<T> type, T service) {
            return RuntimeServiceRegistry.this.register(owner, type, service);
        }

        @Override
        public <T> Optional<T> find(Class<T> type) {
            return RuntimeServiceRegistry.this.find(type);
        }

        @Override
        public <T> List<T> findAll(Class<T> type) {
            return RuntimeServiceRegistry.this.findAll(type);
        }
    }
}

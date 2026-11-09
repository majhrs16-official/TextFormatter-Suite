package me.majhrs16.suite.iflow.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import me.majhrs16.suite.api.iflow.IflowBackend;
import me.majhrs16.suite.api.runtime.ModuleContext;

/**
 * Descubre los backends iFlow activos y aplica la selección del hub.
 *
 * <p>El hub no crea ni cierra motores: cada motor publica un
 * {@link IflowBackend} y gestiona sus propios recursos. Aquí solo se mantiene
 * una referencia al backend activo y se refresca cuando el runtime publica
 * eventos de activación o desactivación de módulos.</p>
 */
final class IflowBackendManager {

    /**
     * Orden de fallback fijado por ADR-032. No depende del orden en que el
     * {@code ServiceRegistry} descubra o active los módulos.
     */
    private static final List<String> FALLBACK_PRIORITY = List.of(
            "iflow-firewall",
            "iflow-vm",
            "iflow-wasm");

    private final ModuleContext context;
    private final IflowConfig config;

    private volatile List<IflowBackend> backends = List.of();
    private volatile List<String> availableBackends = List.of();
    private volatile IflowBackend active;
    private volatile String lastError = "not refreshed";

    IflowBackendManager(ModuleContext context, IflowConfig config) {
        this.context = Objects.requireNonNull(context, "context");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Redescubre servicios y vuelve a elegir backend. Es idempotente y seguro
     * frente a eventos duplicados.
     */
    synchronized void refresh() {
        Map<String, IflowBackend> byName = new LinkedHashMap<>();
        for (IflowBackend backend : context.services().findAll(IflowBackend.class)) {
            if (backend == null || backend.name() == null || backend.name().isBlank()) {
                continue;
            }
            byName.putIfAbsent(backend.name(), backend);
        }

        List<IflowBackend> discovered = new ArrayList<>(byName.values());
        this.backends = List.copyOf(discovered);
        this.availableBackends = discovered.stream().map(IflowBackend::name).toList();

        IflowBackend configured = byName.get(config.engine());
        if (configured != null && configured.isAvailable()) {
            this.active = configured;
            this.lastError = "";
            context.logger().info("iFlow hub selected backend " + configured.name());
            return;
        }

        if (config.fallback()) {
            for (String name : FALLBACK_PRIORITY) {
                IflowBackend candidate = byName.get(name);
                if (candidate != null && candidate.isAvailable()) {
                    this.active = candidate;
                    this.lastError = "configured backend " + config.engine()
                        + " is unavailable; using " + candidate.name();
                    context.logger().warn("iFlow hub " + lastError);
                    return;
                }
            }
        }

        this.active = null;
        this.lastError = configured == null
            ? "configured backend " + config.engine() + " is not installed"
            : "configured backend " + config.engine() + " is unavailable";
        context.logger().warn("iFlow hub has no active backend: " + lastError);
    }

    IflowBackend active() {
        return active;
    }

    /**
     * Resuelve el backend para un override por mensaje.
     *
     * <p>Un nombre desconocido devuelve {@code null}. Un backend instalado pero
     * no disponible aplica la prioridad de fallback de ADR-032 cuando
     * {@code fallback-on-unavailable} está habilitado; si no, devuelve
     * {@code null}.</p>
     */
    IflowBackend forBackend(String requested) {
        if (requested == null || requested.isBlank()) {
            return active;
        }
        IflowBackend named = backendNamed(requested);
        if (named == null) {
            return null;
        }
        if (named.isAvailable()) {
            return named;
        }
        if (config.fallback()) {
            for (String name : FALLBACK_PRIORITY) {
                IflowBackend candidate = backendNamed(name);
                if (candidate != null && candidate.isAvailable()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private IflowBackend backendNamed(String name) {
        for (IflowBackend backend : backends) {
            if (backend.name().equals(name)) {
                return backend;
            }
        }
        return null;
    }

    List<IflowBackend> backends() {
        return backends;
    }

    List<String> availableBackends() {
        return availableBackends;
    }

    String lastError() {
        return lastError;
    }
}

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
            for (IflowBackend candidate : discovered) {
                if (candidate.isAvailable()) {
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

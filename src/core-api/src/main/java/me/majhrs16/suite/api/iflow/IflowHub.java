package me.majhrs16.suite.api.iflow;

import java.util.List;

/**
 * Fachada del hub iFlow.
 *
 * <p>El hub es el único consumidor de {@link IflowBackend} y el proveedor
 * estable de {@link Router} para el host. Expone además el backend activo para
 * diagnóstico y permite refrescar la selección cuando se instala, desinstala o
 * recarga un motor.</p>
 */
public interface IflowHub extends Router {

    /** @return router del backend activo, o {@code null} si no hay ninguno. */
    Router router();

    /**
     * Resuelve el router de un backend concreto para un override por mensaje.
     *
     * <p>El nombre debe ser canónico ({@code iflow-firewall}, {@code iflow-vm},
     * {@code iflow-wasm}). Un backend instalado pero no disponible aplica el
     * fallback de ADR-032 si está habilitado. La selección sigue siendo
     * responsabilidad exclusiva del hub.</p>
     *
     * @param backend nombre canónico del backend, o {@code null}/vacío para el
     *                backend activo
     * @return el router del backend, o {@code null} si el backend no está
     *         instalado o no está disponible y no aplica fallback
     */
    Router router(String backend);

    /** @return nombre del backend activo, o {@code "none"}. */
    String activeBackend();

    /** @return nombres de los backends descubiertos, en orden de registro. */
    List<String> availableBackends();

    /**
     * Redescubre backends y reaplica la selección.
     *
     * @return {@code true} si queda un backend activo disponible
     */
    boolean reload();
}

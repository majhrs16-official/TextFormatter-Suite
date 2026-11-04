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

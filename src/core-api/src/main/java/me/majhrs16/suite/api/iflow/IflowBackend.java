package me.majhrs16.suite.api.iflow;

/**
 * Backend iFlow publicado por un motor instalable.
 *
 * <p>El hub {@code iflow} descubre estos servicios y elige uno según su
 * configuración. Los motores no necesitan exponer tipos de implementación al
 * hub: solo este contrato neutral y un {@link Router}.</p>
 *
 * <p>Un backend puede existir pero no estar disponible (por ejemplo, un
 * artefacto Wasm ausente o con hash incorrecto). En ese caso
 * {@link #isAvailable()} devuelve {@code false} y el hub puede aplicar su
 * política de fallback.</p>
 */
public interface IflowBackend extends AutoCloseable {

    /** @return nombre estable usado por la configuración, p. ej. {@code iflow-wasm}. */
    String name();

    /**
     * @return router del motor; puede ser {@code null} si el backend no está
     *         disponible, aunque lo normal es devolver un router que falla
     *         cerrado.
     */
    Router router();

    /** @return {@code true} si el backend puede evaluar mensajes ahora mismo. */
    boolean isAvailable();

    /**
     * Libera recursos propios del backend. El motor suele trackear su router
     * en el {@code ResourceTracker} del módulo; este método queda para
     * backends que necesiten un cierre adicional.
     */
    @Override
    default void close() {
    }
}

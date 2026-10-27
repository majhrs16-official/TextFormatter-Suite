package me.majhrs16.suite.iflow.firewall;

/**
 * Incidencia detectada al cargar {@code rules.yml}.
 *
 * @param level   severidad
 * @param path    ruta lógica dentro del YAML ({@code guard.max-steps}, {@code nodes[2].id}, ...)
 * @param message descripción legible
 */
public record FirewallIssue(Level level, String path, String message) {

    /** Severidad de una incidencia, equivalente a la del validador del host. */
    public enum Level {
        INFO,
        WARNING,
        ERROR
    }
}

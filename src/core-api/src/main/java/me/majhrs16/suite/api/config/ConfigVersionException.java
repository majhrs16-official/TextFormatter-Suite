package me.majhrs16.suite.api.config;

/**
 * Error de versión de esquema en un archivo de configuración.
 *
 * <p>No es un error de sintaxis YAML: el archivo puede ser válido pero
 * pertenecer a un esquema que la suite ya no interpreta.</p>
 */
public final class ConfigVersionException extends RuntimeException {

    private final String source;
    private final String detail;

    public ConfigVersionException(String source, String detail) {
        super(detail + " [" + source + "]");
        this.source = source;
        this.detail = detail;
    }

    /** @return archivo o descripción del origen. */
    public String source() {
        return source;
    }

    /** @return diagnóstico sin el sufijo de origen. */
    public String detail() {
        return detail;
    }
}

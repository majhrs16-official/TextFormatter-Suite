package me.majhrs16.suite.api.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * Contrato mínimo de versión para los archivos YAML de configuración.
 *
 * <p>Todo archivo de configuración declara {@code config-version: 1.0} en la
 * raíz. La versión identifica el esquema, no la versión de la suite. Un archivo
 * sin la clave se considera editado o corrupto y no se interpreta.</p>
 */
public final class ConfigVersion {

    /** Clave obligatoria en la raíz del YAML. */
    public static final String KEY = "config-version";

    /** Versión de esquema vigente durante el régimen pre-1.0. */
    public static final String EXPECTED = "1.0";

    /** Mensaje exacto para archivos sin la clave. */
    public static final String MISSING_MESSAGE =
            "version de configuracion no identificada (archivo editado o corrupto?)";

    private ConfigVersion() {
    }

    /**
     * Exige la versión vigente en un mapa ya parseado.
     *
     * @param root   raíz YAML
     * @param source archivo de origen para el diagnóstico
     * @throws ConfigVersionException si falta la clave o la versión no es la vigente
     */
    public static void require(Map<?, ?> root, Path source) {
        require(root, source == null ? "<unknown>" : source.toString());
    }

    /**
     * Exige la versión vigente en un mapa ya parseado.
     *
     * @param root   raíz YAML
     * @param source descripción del origen para el diagnóstico
     * @throws ConfigVersionException si falta la clave o la versión no es la vigente
     */
    public static void require(Map<?, ?> root, String source) {
        Object raw = root == null ? null : root.get(KEY);
        if (raw == null) {
            throw new ConfigVersionException(source, MISSING_MESSAGE);
        }
        String actual = String.valueOf(raw).trim();
        if (!EXPECTED.equals(actual)) {
            throw new ConfigVersionException(source,
                    "config-version '" + actual + "' no soportada (se esperaba '"
                            + EXPECTED + "')");
        }
    }
}

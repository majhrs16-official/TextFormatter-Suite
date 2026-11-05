package me.majhrs16.suite.iflow.wasm;

import com.dylibso.chicory.wasm.types.FunctionType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Contrato que un artefacto {@code .wasm} debe cumplir para ejecutarse como
 * política de iFlow.
 *
 * <p>Es intencionadamente restrictivo: sin WASI, sin imports implícitos, sin
 * función de arranque y con un límite de memoria lineal. Cualquier ampliación
 * del contrato debe pasar por el ADR del motor (W1) y versionarse.</p>
 *
 * <p>Las firmas de imports y exports son opcionales. Si
 * {@code allowedImportTypes} está vacío, solo se valida el módulo del import.
 * Si tiene entradas, cada import usado debe ser una función declarada y con la
 * firma exacta. Las firmas de exports se validan siempre que
 * {@code requiredExportTypes} tenga una entrada.</p>
 *
 * @param requiredExports      exports obligatorios, p. ej. {@code decide}
 * @param requiredExportTypes  firmas obligatorias de exports, por nombre
 * @param allowedImportModules módulos de import permitidos (vacío = ninguno)
 * @param allowedImportTypes   firmas permitidas de imports, por {@code "módulo.nombre"}
 * @param maxMemoryPages       máximo de páginas WebAssembly de 64 KiB
 * @param allowStart           si se permite la sección {@code start}
 */
public record WasmContract(
        Set<String> requiredExports,
        Map<String, FunctionType> requiredExportTypes,
        Set<String> allowedImportModules,
        Map<String, FunctionType> allowedImportTypes,
        int maxMemoryPages,
        boolean allowStart
) {

    /** Límite duro de tamaño del artefacto en disco: 4 MiB. */
    public static final int MAX_ARTIFACT_BYTES = 4 * 1024 * 1024;

    public WasmContract {
        Objects.requireNonNull(requiredExports, "requiredExports");
        Objects.requireNonNull(requiredExportTypes, "requiredExportTypes");
        Objects.requireNonNull(allowedImportModules, "allowedImportModules");
        Objects.requireNonNull(allowedImportTypes, "allowedImportTypes");
        if (maxMemoryPages < 1) {
            throw new IllegalArgumentException("maxMemoryPages must be >= 1");
        }
        requiredExports = Set.copyOf(new LinkedHashSet<>(requiredExports));
        requiredExportTypes = Map.copyOf(new LinkedHashMap<>(requiredExportTypes));
        allowedImportModules = Set.copyOf(new LinkedHashSet<>(allowedImportModules));
        allowedImportTypes = Map.copyOf(new LinkedHashMap<>(allowedImportTypes));

        for (String export : requiredExportTypes.keySet()) {
            if (!requiredExports.contains(export)) {
                throw new IllegalArgumentException(
                    "requiredExportTypes contiene un export no obligatorio: " + export);
            }
        }
        for (String importKey : allowedImportTypes.keySet()) {
            int dot = importKey.indexOf('.');
            if (dot <= 0 || dot == importKey.length() - 1) {
                throw new IllegalArgumentException(
                    "allowedImportTypes requiere claves 'módulo.nombre': " + importKey);
            }
            String module = importKey.substring(0, dot);
            if (!allowedImportModules.contains(module)) {
                throw new IllegalArgumentException(
                    "allowedImportTypes declara un módulo no permitido: " + importKey);
            }
        }
    }

    /**
     * Contrato por defecto para políticas iFlow v3: export {@code decide},
     * imports del módulo {@code iflow} con firmas explícitas, 256 páginas
     * (16 MiB) y sin {@code start}.
     */
    public static WasmContract policyV3() {
        return new WasmContract(
            Set.of(PolicyAbiV3.EXPORT_DECIDE),
            PolicyAbiV3.exports(),
            Set.of(PolicyAbiV3.IMPORT_MODULE),
            PolicyAbiV3.imports(),
            256,
            false
        );
    }
}

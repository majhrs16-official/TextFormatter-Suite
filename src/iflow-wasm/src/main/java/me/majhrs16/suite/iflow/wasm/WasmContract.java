package me.majhrs16.suite.iflow.wasm;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Contrato mínimo que un artefacto {@code .wasm} debe cumplir para ejecutarse
 * como política de iFlow.
 *
 * <p>Es intencionadamente restrictivo: sin WASI, sin imports implícitos, sin
 * función de arranque y con un límite de memoria lineal. Cualquier ampliación
 * del contrato debe pasar por el ADR del motor (W1) y versionarse.</p>
 *
 * @param requiredExports      exports obligatorios, p. ej. {@code decide}
 * @param allowedImportModules módulos de import permitidos (vacío = ninguno)
 * @param maxMemoryPages       máximo de páginas WebAssembly de 64 KiB
 * @param allowStart           si se permite la sección {@code start}
 */
public record WasmContract(
        Set<String> requiredExports,
        Set<String> allowedImportModules,
        int maxMemoryPages,
        boolean allowStart
) {

    /** Límite duro de tamaño del artefacto en disco: 4 MiB. */
    public static final int MAX_ARTIFACT_BYTES = 4 * 1024 * 1024;

    public WasmContract {
        Objects.requireNonNull(requiredExports, "requiredExports");
        Objects.requireNonNull(allowedImportModules, "allowedImportModules");
        if (maxMemoryPages < 1) {
            throw new IllegalArgumentException("maxMemoryPages must be >= 1");
        }
        requiredExports = Set.copyOf(new LinkedHashSet<>(requiredExports));
        allowedImportModules = Set.copyOf(new LinkedHashSet<>(allowedImportModules));
    }

    /**
     * Contrato por defecto para políticas iFlow v3: export {@code decide},
     * sin imports, 256 páginas (16 MiB) y sin {@code start}.
     */
    public static WasmContract policyV3() {
        return new WasmContract(Set.of("decide"), Set.of(), 256, false);
    }
}

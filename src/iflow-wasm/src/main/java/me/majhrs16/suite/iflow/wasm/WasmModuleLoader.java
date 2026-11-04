package me.majhrs16.suite.iflow.wasm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.Export;
import com.dylibso.chicory.wasm.types.ExportSection;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Import;
import com.dylibso.chicory.wasm.types.ImportSection;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.MemorySection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adapter mínimo de Chicory para la línea {@code iflow-wasm}.
 *
 * <p>Cubre el alcance de W2: cargar un artefacto {@code .wasm}, validarlo
 * contra un {@link WasmContract}, instanciarlo con memoria acotada y export
 * {@code start} controlado, e invocar un export concreto. El motor de
 * políticas ({@code WasmPolicyEngine}), los imports de host y la integración
 * con {@code Router} pertenecen a W3.</p>
 */
public final class WasmModuleLoader {

    /**
     * Parsea un módulo WebAssembly desde disco aplicando el límite de tamaño.
     *
     * @param wasmFile ruta al artefacto {@code .wasm}
     * @return el módulo parseado
     * @throws NullPointerException     si {@code wasmFile} es {@code null}
     * @throws IllegalArgumentException si el artefacto supera {@link WasmContract#MAX_ARTIFACT_BYTES}
     * @throws ChicoryException         si el artefacto no es un módulo válido
     */
    public WasmModule load(Path wasmFile) {
        Objects.requireNonNull(wasmFile, "wasmFile");
        checkSize(readSize(wasmFile));
        return Parser.parse(wasmFile);
    }

    /**
     * Parsea un módulo WebAssembly desde memoria aplicando el límite de tamaño.
     *
     * @param wasmBytes bytes del artefacto
     * @return el módulo parseado
     * @throws NullPointerException     si {@code wasmBytes} es {@code null}
     * @throws IllegalArgumentException si está vacío o supera
     *                                  {@link WasmContract#MAX_ARTIFACT_BYTES}
     * @throws ChicoryException         si los bytes no son un módulo válido
     */
    public WasmModule load(byte[] wasmBytes) {
        Objects.requireNonNull(wasmBytes, "wasmBytes");
        if (wasmBytes.length == 0) {
            throw new IllegalArgumentException("wasmBytes is empty");
        }
        checkSize(wasmBytes.length);
        return Parser.parse(wasmBytes);
    }

    /**
     * Crea una instancia aislada sin contrato explícito.
     *
     * <p>Pensado para el scaffold W0 y pruebas de bajo nivel; el camino de
     * políticas debe usar {@link #instantiate(WasmModule, WasmContract)}.</p>
     *
     * @param module módulo ya parseado
     * @return instancia nueva con su propia memoria lineal
     */
    public Instance instantiate(WasmModule module) {
        Objects.requireNonNull(module, "module");
        return Instance.builder(module).build();
    }

    /**
     * Valida un módulo contra el contrato y crea una instancia acotada.
     *
     * @param module   módulo ya parseado
     * @param contract contrato de exports, imports, memoria y {@code start}
     * @return instancia nueva
     * @throws IllegalArgumentException si el módulo incumple el contrato
     */
    public Instance instantiate(WasmModule module, WasmContract contract) {
        return instantiate(module, contract, ImportValues.builder().build());
    }

    /**
     * Valida un módulo contra el contrato, enlaza los imports del host y crea
     * una instancia acotada.
     *
     * <p>El llamador es responsable de que {@code imports} cubra exactamente
     * las funciones declaradas por el módulo; Chicory falla al instanciar si
     * falta alguna. El contrato ya validó nombres y firmas antes de llegar
     * aquí.</p>
     *
     * @param module   módulo ya parseado
     * @param contract contrato de exports, imports, memoria y {@code start}
     * @param imports  funciones de host a enlazar
     * @return instancia nueva
     * @throws IllegalArgumentException si el módulo incumple el contrato
     */
    public Instance instantiate(WasmModule module, WasmContract contract, ImportValues imports) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(imports, "imports");
        ValidationResult validation = validate(module, contract);
        if (!validation.valid()) {
            throw new IllegalArgumentException("artefacto inválido: " + validation.errors());
        }
        Instance.Builder builder = Instance.builder(module)
            .withStart(contract.allowStart())
            .withImportValues(imports);
        MemoryLimits limits = cappedMemoryLimits(module, contract);
        if (limits != null) {
            builder.withMemoryLimits(limits);
        }
        return builder.build();
    }

    /**
     * Valida un módulo contra un contrato sin instanciarlo.
     *
     * <p>Comprueba imports permitidos, exports obligatorios, {@code start} y
     * límites de memoria declarados.</p>
     *
     * @param module   módulo ya parseado
     * @param contract contrato a cumplir
     * @return errores encontrados; lista vacía si es válido
     */
    public ValidationResult validate(WasmModule module, WasmContract contract) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(contract, "contract");
        List<String> errors = new ArrayList<>();

        validateImports(module, contract, errors);
        validateExports(module, contract, errors);

        if (module.startSection().isPresent() && !contract.allowStart()) {
            errors.add("sección start no permitida por el contrato");
        }

        module.memorySection().ifPresent(memory -> {
            if (memory.memoryCount() > 1) {
                errors.add("memorias múltiples no soportadas (encontradas: " + memory.memoryCount() + ")");
                return;
            }
            if (memory.memoryCount() == 1) {
                MemoryLimits limits = memory.getMemory(0).limits();
                if (limits.initialPages() > contract.maxMemoryPages()) {
                    errors.add("memoria inicial " + limits.initialPages()
                        + " páginas supera el máximo " + contract.maxMemoryPages());
                }
            }
        });

        return new ValidationResult(errors);
    }

    /**
     * Invoca un export del módulo y devuelve sus resultados crudos.
     *
     * @param instance   instancia ya creada
     * @param exportName nombre del export
     * @param args       argumentos de 64 bits
     * @return resultados de 64 bits
     * @throws IllegalArgumentException si el export no existe
     */
    public long[] invoke(Instance instance, String exportName, long... args) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(exportName, "exportName");
        ExportFunction function;
        try {
            function = instance.export(exportName);
        } catch (ChicoryException e) {
            throw new IllegalArgumentException("export no encontrado: " + exportName, e);
        }
        if (function == null) {
            throw new IllegalArgumentException("export no encontrado: " + exportName);
        }
        return function.apply(args == null ? new long[0] : args);
    }

    private static void validateImports(WasmModule module, WasmContract contract, List<String> errors) {
        ImportSection imports = module.importSection();
        if (imports == null) {
            return;
        }
        for (int i = 0; i < imports.importCount(); i++) {
            Import imp = imports.getImport(i);
            String key = imp.module() + "." + imp.name();
            if (!contract.allowedImportModules().contains(imp.module())) {
                errors.add("import no permitido: " + key);
                continue;
            }
            if (contract.allowedImportTypes().isEmpty()) {
                continue;
            }
            if (!(imp instanceof FunctionImport functionImport)) {
                errors.add("import no funcional no permitido: " + key);
                continue;
            }
            FunctionType expected = contract.allowedImportTypes().get(key);
            if (expected == null) {
                errors.add("import no declarado: " + key);
                continue;
            }
            FunctionType actual = functionImportType(module, functionImport);
            if (!matches(actual, expected)) {
                errors.add("firma de import incorrecta: " + key
                    + " esperada " + expected + " y encontrada " + actual);
            }
        }
    }

    private static void validateExports(WasmModule module, WasmContract contract, List<String> errors) {
        ExportSection exports = module.exportSection();
        Set<String> exportNames = new HashSet<>();
        if (exports != null) {
            for (int i = 0; i < exports.exportCount(); i++) {
                Export export = exports.getExport(i);
                exportNames.add(export.name());
                FunctionType expected = contract.requiredExportTypes().get(export.name());
                if (expected == null) {
                    continue;
                }
                if (export.exportType() != ExternalType.FUNCTION) {
                    errors.add("export no funcional: " + export.name());
                    continue;
                }
                FunctionType actual = exportFunctionType(module, export);
                if (!matches(actual, expected)) {
                    errors.add("firma de export incorrecta: " + export.name()
                        + " esperada " + expected + " y encontrada " + actual);
                }
            }
        }
        for (String required : contract.requiredExports()) {
            if (!exportNames.contains(required)) {
                errors.add("export requerido ausente: " + required);
            }
        }
    }

    private static FunctionType functionImportType(WasmModule module, FunctionImport imp) {
        return module.typeSection().getType(imp.typeIndex());
    }

    private static FunctionType exportFunctionType(WasmModule module, Export export) {
        if (module.functionSection() == null) {
            return null;
        }
        int functionIndex = export.index() - countFunctionImports(module);
        if (functionIndex < 0) {
            return null;
        }
        try {
            return module.functionSection().getFunctionType(functionIndex, module.typeSection());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int countFunctionImports(WasmModule module) {
        ImportSection imports = module.importSection();
        if (imports == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < imports.importCount(); i++) {
            if (imports.getImport(i) instanceof FunctionImport) {
                count++;
            }
        }
        return count;
    }

    private static boolean matches(FunctionType actual, FunctionType expected) {
        return actual != null
            && actual.paramsMatch(expected)
            && actual.returnsMatch(expected);
    }

    private static void checkSize(long bytes) {
        if (bytes > WasmContract.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("artefacto demasiado grande: " + bytes
                + " bytes (máximo " + WasmContract.MAX_ARTIFACT_BYTES + ")");
        }
    }

    private static long readSize(Path wasmFile) {
        try {
            return Files.size(wasmFile);
        } catch (IOException e) {
            throw new ChicoryException("no se pudo leer el artefacto: " + wasmFile, e);
        }
    }

    private static MemoryLimits cappedMemoryLimits(WasmModule module, WasmContract contract) {
        MemoryLimits declared = module.memorySection()
            .filter(memory -> memory.memoryCount() == 1)
            .map(memory -> memory.getMemory(0).limits())
            .orElse(null);
        if (declared == null) {
            return null;
        }
        int initial = declared.initialPages();
        int maximum = Math.min(contract.maxMemoryPages(), MemoryLimits.MAX_PAGES);
        return new MemoryLimits(initial, maximum);
    }
}

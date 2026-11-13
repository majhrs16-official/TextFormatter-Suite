package me.majhrs16.suite.iflow.wasm;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.WasmModule;

import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.sync.Principal;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Ejecuta una política {@code .wasm} contra un mensaje y devuelve un
 * {@link PolicyOutcome}.
 *
 * <p>El engine crea una {@link PolicySession} por evaluación, enlaza las host
 * functions de {@link PolicyHost}, invoca el export {@code decide} y valida el
 * estado. Cualquier fallo de carga, instanciación o ejecución se convierte en
 * {@link PolicyAction#ERROR}; el host no recibe excepciones de un artefacto no
 * confiable.</p>
 *
 * <p>La integración con {@code Router} y la etapa {@code route} pertenece a
 * W3; este engine ya permite probar el contrato v3 de forma aislada.</p>
 */
public final class WasmPolicyEngine {

    private final WasmModuleLoader loader;
    private volatile Supplier<SyncBus> syncBus = () -> null;

    public WasmPolicyEngine() {
        this(new WasmModuleLoader());
    }

    public WasmPolicyEngine(WasmModuleLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Instala el resolvedor del bus sync. Se evalúa por invocación para tolerar
     * el orden de activación de módulos.
     *
     * @param supplier resuelve el bus, o {@code null} cuando no está instalado
     * @return este engine
     */
    public WasmPolicyEngine syncBus(Supplier<SyncBus> supplier) {
        this.syncBus = Objects.requireNonNull(supplier, "supplier");
        return this;
    }

    public PolicyOutcome evaluate(Path wasmFile, PolicyRequest request) {
        Objects.requireNonNull(wasmFile, "wasmFile");
        Objects.requireNonNull(request, "request");
        try {
            return evaluate(loader.load(wasmFile), request);
        } catch (RuntimeException e) {
            return PolicyOutcome.error(request.message(), "artefacto inválido: " + message(e));
        }
    }

    public PolicyOutcome evaluate(WasmModule module, PolicyRequest request) {
        return evaluate(module, request, null);
    }

    /**
     * Evalúa el módulo con la identidad SHA-256 del artefacto.
     *
     * @param module    módulo validado
     * @param request   petición de la unidad de routing
     * @param principal identidad del programa, o {@code null} si no está
     *                  verificado (el puente sync la rechaza explícitamente)
     * @return el resultado de la política
     */
    public PolicyOutcome evaluate(WasmModule module, PolicyRequest request,
                                  Principal.Program principal) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(request, "request");
        PolicySession session = new PolicySession(request, principal, syncBus);
        try {
            PolicyHost host = new PolicyHost(session);
            Instance instance = loader.instantiate(
                module, WasmContract.policyV3(), host.importValues());
            long[] result = loader.invoke(instance, PolicyAbiV3.EXPORT_DECIDE);
            if (result.length != 1) {
                return session.failure("decide no devolvió un i32");
            }
            int status = (int) result[0];
            if (status != PolicyAbiV3.STATUS_OK) {
                return session.failure("decide devolvió estado " + status);
            }
            return session.outcome();
        } catch (RuntimeException e) {
            return session.failure("fallo de ejecución: " + message(e));
        }
    }

    private static String message(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
            ? e.getClass().getSimpleName()
            : message;
    }
}

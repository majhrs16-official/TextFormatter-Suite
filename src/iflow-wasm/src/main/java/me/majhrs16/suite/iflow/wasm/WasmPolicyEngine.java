package me.majhrs16.suite.iflow.wasm;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.WasmModule;

import java.nio.file.Path;
import java.util.Objects;

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
 * <p>La integración con {@code Router}/{@code MessageDispatcher} pertenece a
 * W3; este engine ya permite probar el contrato v3 de forma aislada.</p>
 */
public final class WasmPolicyEngine {

    private final WasmModuleLoader loader;

    public WasmPolicyEngine() {
        this(new WasmModuleLoader());
    }

    public WasmPolicyEngine(WasmModuleLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
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
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(request, "request");
        PolicySession session = new PolicySession(request);
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

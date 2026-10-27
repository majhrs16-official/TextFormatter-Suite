package me.majhrs16.suite.iflow.wasm;

import java.util.List;

/**
 * Resultado de validar un artefacto contra un {@link WasmContract}.
 *
 * @param errors lista de incumplimientos; vacía si el artefacto es válido
 */
public record ValidationResult(List<String> errors) {

    public ValidationResult {
        errors = List.copyOf(errors);
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    public static ValidationResult ok() {
        return new ValidationResult(List.of());
    }
}

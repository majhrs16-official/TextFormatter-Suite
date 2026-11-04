package me.majhrs16.suite.iflow.wasm;

import me.majhrs16.suite.api.message.Message;

import java.util.Objects;

/**
 * Resultado de evaluar una política {@code .wasm} para un mensaje y recipient.
 *
 * @param action  acción final; nunca {@code null}
 * @param target  destino informativo declarado por la política; cadena vacía si no aplica
 * @param reason  razón legible; cadena vacía si no aplica
 * @param message mensaje con las transformaciones del journal aplicadas
 */
public record PolicyOutcome(
        PolicyAction action,
        String target,
        String reason,
        Message message
) {

    public PolicyOutcome {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(message, "message");
        target = target == null ? "" : target;
        reason = reason == null ? "" : reason;
    }

    public boolean delivered() {
        return action == PolicyAction.DELIVER;
    }

    public static PolicyOutcome deliver(Message message) {
        return new PolicyOutcome(PolicyAction.DELIVER, "", "", message);
    }

    public static PolicyOutcome cancel(Message message, String reason) {
        return new PolicyOutcome(PolicyAction.CANCEL_EVENT, "", reason, message);
    }

    public static PolicyOutcome error(Message message, String reason) {
        return new PolicyOutcome(PolicyAction.ERROR, "", reason, message);
    }
}

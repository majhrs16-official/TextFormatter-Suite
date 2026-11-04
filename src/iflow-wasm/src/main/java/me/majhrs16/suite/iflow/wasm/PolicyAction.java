package me.majhrs16.suite.iflow.wasm;

/**
 * Acción final de una política iFlow v3.
 *
 * <p>No existe redirección: un cambio de canal se expresa con
 * {@code set_channel} antes de decidir. La acción solo indica qué hace el host
 * con el mensaje ya transformado.</p>
 */
public enum PolicyAction {

    /** Entregar el mensaje al recipient actual. */
    DELIVER(PolicyAbiV3.ACTION_DELIVER),

    /** Cancelar el evento de plataforma; no hay entrega. */
    CANCEL_EVENT(PolicyAbiV3.ACTION_CANCEL_EVENT),

    /** Error controlado; no hay entrega. */
    ERROR(PolicyAbiV3.ACTION_ERROR);

    private final int code;

    PolicyAction(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static PolicyAction fromCode(int code) {
        for (PolicyAction action : values()) {
            if (action.code == code) {
                return action;
            }
        }
        throw new IllegalArgumentException("acción de política desconocida: " + code);
    }
}

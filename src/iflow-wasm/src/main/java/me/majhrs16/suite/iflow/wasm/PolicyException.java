package me.majhrs16.suite.iflow.wasm;

/**
 * Error controlado de una operación de política.
 *
 * <p>El {@link #status()} usa los códigos de {@link PolicyAbiV3}; las host
 * functions lo traducen al valor de retorno del import sin propagar una
 * excepción al guest.</p>
 */
public final class PolicyException extends RuntimeException {

    private final int status;

    public PolicyException(int status, String message) {
        super(message);
        this.status = status;
    }

    public PolicyException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}

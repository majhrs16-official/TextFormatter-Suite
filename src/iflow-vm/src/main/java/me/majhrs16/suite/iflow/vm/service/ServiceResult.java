package me.majhrs16.suite.iflow.vm.service;

/**
 * Result of a host service invocation (HOST ABI v2).
 * <p>
 * The {@code HOST} instruction reports its outcome through registers:
 * </p>
 * <ul>
 *   <li>{@code R0} = {@link Status} code (see {@link Status#code()}) — a dedicated
 *       status register, so failures are distinguishable from an OK of 0 bytes.</li>
 *   <li>{@code R3} = bytes transferred. {@code length} is bidirectional: on input it
 *       is the requested size; on output, for reads, it is the number of bytes the
 *       emulator wrote to VM memory, and for writes, the number of bytes consumed.</li>
 *   <li>{@code R4} = service-specific scalar result (e.g. {@code 0/1} for permission
 *       checks, the decision code for routing).</li>
 * </ul>
 * <p>
 * {@code R1} (options) and {@code R2} (base) are left untouched by the call.
 * </p>
 */
public final class ServiceResult {

    /**
     * Status codes exposed to the program in {@code R0}.
     * <p>
     * The numeric values are part of the ABI: they are what the program compares
     * against, so they must never be reordered.
     * </p>
     */
    public enum Status {
        OK(0),
        NOT_FOUND(1),
        DENIED(2),
        INVALID_ARGUMENT(3),
        BUFFER_TOO_SMALL(4),
        UNSUPPORTED(5),
        CONFLICT(6),
        HOST_FAILURE(7),
        PARTIAL(8);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        /** Numeric code written to {@code R0}. */
        public int code() {
            return code;
        }
    }

    private final Status status;
    private final int bytesTransferred; // R3 on return
    private final int result;           // R4 on return
    private final String detail;        // diagnostic only; never crosses the ABI

    private ServiceResult(Status status, int bytesTransferred, int result) {
        this(status, bytesTransferred, result, null);
    }

    private ServiceResult(Status status, int bytesTransferred, int result, String detail) {
        this.status = status;
        this.bytesTransferred = bytesTransferred;
        this.result = result;
        this.detail = detail;
    }

    /** Success with {@code bytesTransferred} bytes and no scalar result. */
    public static ServiceResult ok(int bytesTransferred) {
        return new ServiceResult(Status.OK, bytesTransferred, 0);
    }

    /** Success with {@code bytesTransferred} bytes and a scalar result in {@code R4}. */
    public static ServiceResult ok(int bytesTransferred, int result) {
        return new ServiceResult(Status.OK, bytesTransferred, result);
    }

    public static ServiceResult notFound() {
        return new ServiceResult(Status.NOT_FOUND, 0, 0);
    }

    /** {@link #notFound()} with a diagnostic reason. */
    public static ServiceResult notFound(String detail) {
        return new ServiceResult(Status.NOT_FOUND, 0, 0, detail);
    }

    public static ServiceResult denied() {
        return new ServiceResult(Status.DENIED, 0, 0);
    }

    /** {@link #denied()} with a diagnostic reason. */
    public static ServiceResult denied(String detail) {
        return new ServiceResult(Status.DENIED, 0, 0, detail);
    }

    public static ServiceResult invalidArgument(String msg) {
        return new ServiceResult(Status.INVALID_ARGUMENT, 0, 0, msg);
    }

    /** {@code R3} carries the required buffer size so the caller can retry. */
    public static ServiceResult bufferTooSmall(int required) {
        return new ServiceResult(Status.BUFFER_TOO_SMALL, required, 0,
            "required " + required + " bytes");
    }

    public static ServiceResult unsupported() {
        return new ServiceResult(Status.UNSUPPORTED, 0, 0);
    }

    /** {@link #unsupported()} with a diagnostic reason. */
    public static ServiceResult unsupported(String detail) {
        return new ServiceResult(Status.UNSUPPORTED, 0, 0, detail);
    }

    public static ServiceResult conflict() {
        return new ServiceResult(Status.CONFLICT, 0, 0);
    }

    /** {@link #conflict()} with a diagnostic reason. */
    public static ServiceResult conflict(String detail) {
        return new ServiceResult(Status.CONFLICT, 0, 0, detail);
    }

    public static ServiceResult hostFailure(String msg) {
        return new ServiceResult(Status.HOST_FAILURE, 0, 0, msg);
    }

    public static ServiceResult partial(int bytesTransferred, int result) {
        return new ServiceResult(Status.PARTIAL, bytesTransferred, result);
    }

    public Status status() { return status; }
    public int bytesTransferred() { return bytesTransferred; }
    public int result() { return result; }

    /**
     * Human-readable reason for a non-OK status, or {@code null}. Diagnostic
     * only: it never crosses the HOST ABI, which still exposes just
     * {@code R0/R3/R4}. Callers log it; the program cannot read it.
     */
    public String detail() { return detail; }

    public boolean hasDetail() { return detail != null && !detail.isEmpty(); }

    public boolean isSuccess() { return status == Status.OK || status == Status.PARTIAL; }
    public boolean isError() { return !isSuccess(); }

    @Override
    public String toString() {
        String base = "ServiceResult{" + status + "(R0=" + status.code() + "), R3=" + bytesTransferred
            + ", R4=" + result;
        return detail == null ? base + "}" : base + ", detail=" + detail + "}";
    }
}

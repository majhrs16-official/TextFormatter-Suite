package me.majhrs16.suite.iflow.vm.service;

import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.isa.VmException;

/**
 * Result of a host service invocation.
 */
public final class ServiceResult {

    public enum Status {
        OK,
        NOT_FOUND,
        DENIED,
        INVALID_ARGUMENT,
        BUFFER_TOO_SMALL,
        UNSUPPORTED,
        CONFLICT,
        HOST_FAILURE,
        PARTIAL
    }

    private final Status status;
    private final int primaryResult;      // R0 on return
    private final int bytesTransferred;   // R1 on return (for reads)
    private final int auxResult;          // R3 on return

    private ServiceResult(Status status, int primaryResult, int bytesTransferred, int auxResult) {
        this.status = status;
        this.primaryResult = primaryResult;
        this.bytesTransferred = bytesTransferred;
        this.auxResult = auxResult;
    }

    public static ServiceResult ok(int primaryResult, int bytesTransferred, int auxResult) {
        return new ServiceResult(Status.OK, primaryResult, bytesTransferred, auxResult);
    }

    public static ServiceResult ok(int primaryResult) {
        return new ServiceResult(Status.OK, primaryResult, 0, 0);
    }

    public static ServiceResult notFound() {
        return new ServiceResult(Status.NOT_FOUND, 0, 0, 0);
    }

    public static ServiceResult denied() {
        return new ServiceResult(Status.DENIED, 0, 0, 0);
    }

    public static ServiceResult invalidArgument(String msg) {
        return new ServiceResult(Status.INVALID_ARGUMENT, 0, 0, 0);
    }

    public static ServiceResult bufferTooSmall(int required) {
        return new ServiceResult(Status.BUFFER_TOO_SMALL, required, 0, 0);
    }

    public static ServiceResult unsupported() {
        return new ServiceResult(Status.UNSUPPORTED, 0, 0, 0);
    }

    public static ServiceResult conflict() {
        return new ServiceResult(Status.CONFLICT, 0, 0, 0);
    }

    public static ServiceResult hostFailure(String msg) {
        return new ServiceResult(Status.HOST_FAILURE, 0, 0, 0);
    }

    public static ServiceResult partial(int primaryResult, int bytesTransferred) {
        return new ServiceResult(Status.PARTIAL, primaryResult, bytesTransferred, 0);
    }

    public Status status() { return status; }
    public int primaryResult() { return primaryResult; }
    public int bytesTransferred() { return bytesTransferred; }
    public int auxResult() { return auxResult; }

    public boolean isSuccess() { return status == Status.OK || status == Status.PARTIAL; }
    public boolean isError() { return !isSuccess(); }

    @Override
    public String toString() {
        return "ServiceResult{" + status + ", R0=" + primaryResult + ", R1=" + bytesTransferred + ", R3=" + auxResult + "}";
    }
}
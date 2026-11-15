package me.majhrs16.suite.iflow.vm.host.v3;

import java.util.Objects;

import me.majhrs16.suite.iflow.vm.isa.v3.HostStatus;

/**
 * Explicit, non-success status raised by a v3 host-service implementation.
 */
public final class HostServiceException extends Exception {

    private final int status;

    public HostServiceException(int status, String detail) {
        super(Objects.requireNonNull(detail, "detail"));
        if (!HostStatus.isValid(status) || status == HostStatus.OK) {
            throw new IllegalArgumentException("Not an error HOST status: " + status);
        }
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static HostServiceException invalidArgument(String detail) {
        return new HostServiceException(HostStatus.INVALID_ARGUMENT, detail);
    }

    public static HostServiceException unsupported(String detail) {
        return new HostServiceException(HostStatus.UNSUPPORTED, detail);
    }

    public static HostServiceException denied(String detail) {
        return new HostServiceException(HostStatus.DENIED, detail);
    }

    public static HostServiceException notFound(String detail) {
        return new HostServiceException(HostStatus.NOT_FOUND, detail);
    }

    public static HostServiceException conflict(String detail) {
        return new HostServiceException(HostStatus.CONFLICT, detail);
    }

    public static HostServiceException notReady(String detail) {
        return new HostServiceException(HostStatus.NOT_READY, detail);
    }
}

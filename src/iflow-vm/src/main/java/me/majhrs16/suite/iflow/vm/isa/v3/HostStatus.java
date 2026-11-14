package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Result codes of the v3 {@code HOST} ABI.
 */
public final class HostStatus {

    public static final int OK = 0;
    public static final int INVALID_ARGUMENT = 1;
    public static final int UNSUPPORTED = 2;
    public static final int DENIED = 3;
    public static final int NOT_FOUND = 4;
    public static final int CONFLICT = 5;
    public static final int LIMIT = 6;
    public static final int FAULT = 7;
    public static final int INTERNAL = 8;
    public static final int NOT_READY = 9;
    public static final int RATE_LIMITED = 10;
    public static final int TIMEOUT = 11;

    private HostStatus() {
    }

    public static boolean isValid(int status) {
        return status >= OK && status <= TIMEOUT;
    }
}

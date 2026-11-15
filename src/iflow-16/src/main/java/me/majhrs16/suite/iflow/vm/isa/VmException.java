package me.majhrs16.suite.iflow.vm.isa;

/**
 * VM execution exceptions.
 */
public final class VmException extends RuntimeException {

    public enum Code {
        INVALID_OPCODE,
        INVALID_OPERAND,
        MEMORY_FAULT,
        ARITHMETIC_FAULT,
        PRIVILEGE_FAULT,
        HOST_ERROR,
        OUT_OF_FUEL,
        STACK_LIMIT,
        CANCELLED,
        INVALID_JUMP,
        DIVISION_BY_ZERO,
        INVALID_SHIFT_COUNT,
        INVALID_REGION,
        REGION_PERMISSION,
        REGION_BOUNDS,
        HOST_SERVICE_NOT_FOUND,
        HOST_SERVICE_DENIED,
        HOST_BUFFER_TOO_SMALL,
        HOST_INVALID_ARGUMENT,
        HOST_CONFLICT,
        HOST_FAILURE,
        PROGRAM_NOT_FOUND,
        PROGRAM_VERSION_MISMATCH,
        INVALID_PROGRAM,
        CAPABILITY_DENIED
    }

    private final Code code;
    private final int ip;

    public VmException(Code code, String message) {
        this(code, message, -1);
    }

    public VmException(Code code, String message, int ip) {
        super(message);
        this.code = code;
        this.ip = ip;
    }

    public VmException(Code code, String message, Throwable cause) {
        this(code, message, -1);
        initCause(cause);
    }

    public VmException(Code code, String message, int ip, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.ip = ip;
    }

    public Code code() {
        return code;
    }

    public int ip() {
        return ip;
    }

    public static VmException invalidOpcode(int opcode, int ip) {
        return new VmException(Code.INVALID_OPCODE,
            "Invalid opcode 0x" + Integer.toHexString(opcode).toUpperCase(), ip);
    }

    public static VmException invalidOperand(String detail, int ip) {
        return new VmException(Code.INVALID_OPERAND, detail, ip);
    }

    public static VmException memoryFault(String detail, int ip) {
        return new VmException(Code.MEMORY_FAULT, detail, ip);
    }

    public static VmException arithmeticFault(String detail, int ip) {
        return new VmException(Code.ARITHMETIC_FAULT, detail, ip);
    }

    public static VmException privilegeFault(String detail, int ip) {
        return new VmException(Code.PRIVILEGE_FAULT, detail, ip);
    }

    public static VmException hostError(String detail, int ip) {
        return new VmException(Code.HOST_ERROR, detail, ip);
    }

    public static VmException outOfFuel(int ip) {
        return new VmException(Code.OUT_OF_FUEL, "Fuel exhausted", ip);
    }

    public static VmException stackLimit(int ip) {
        return new VmException(Code.STACK_LIMIT, "Stack limit exceeded", ip);
    }

    public static VmException cancelled(int ip) {
        return new VmException(Code.CANCELLED, "Execution cancelled", ip);
    }

    public static VmException invalidJump(int target, int ip) {
        return new VmException(Code.INVALID_JUMP,
            "Invalid jump target: 0x" + Integer.toHexString(target).toUpperCase(), ip);
    }

    public static VmException divisionByZero(int ip) {
        return new VmException(Code.DIVISION_BY_ZERO, "Division by zero", ip);
    }

    public static VmException invalidShiftCount(int count, int ip) {
        return new VmException(Code.INVALID_SHIFT_COUNT,
            "Invalid shift count: " + count + " (must be 0-15)", ip);
    }

    public static VmException invalidRegion(int handle, int ip) {
        return new VmException(Code.INVALID_REGION,
            "Invalid region handle: " + handle, ip);
    }

    public static VmException regionPermission(int handle, String perm, int ip) {
        return new VmException(Code.REGION_PERMISSION,
            "Region " + handle + " permission denied: " + perm, ip);
    }

    public static VmException regionBounds(int handle, int offset, int length, int ip) {
        return new VmException(Code.REGION_BOUNDS,
            "Region " + handle + " bounds exceeded: offset=" + offset + " length=" + length, ip);
    }

    public static VmException hostServiceNotFound(int serviceId, int ip) {
        return new VmException(Code.HOST_SERVICE_NOT_FOUND,
            "Host service not found: 0x" + Integer.toHexString(serviceId).toUpperCase(), ip);
    }

    public static VmException hostServiceDenied(int serviceId, int ip) {
        return new VmException(Code.HOST_SERVICE_DENIED,
            "Host service denied: 0x" + Integer.toHexString(serviceId).toUpperCase(), ip);
    }

    public static VmException hostBufferTooSmall(int serviceId, int required, int available, int ip) {
        return new VmException(Code.HOST_BUFFER_TOO_SMALL,
            "Buffer too small for service 0x" + Integer.toHexString(serviceId).toUpperCase()
            + ": need " + required + " have " + available, ip);
    }

    public static VmException hostInvalidArgument(int serviceId, String detail, int ip) {
        return new VmException(Code.HOST_INVALID_ARGUMENT,
            "Invalid argument for service 0x" + Integer.toHexString(serviceId).toUpperCase()
            + ": " + detail, ip);
    }
}
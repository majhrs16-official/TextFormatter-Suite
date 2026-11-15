package me.majhrs16.suite.iflow.vm.isa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VmExceptionTest {

    @Test
    void basicConstructor() {
        VmException e = new VmException(VmException.Code.INVALID_OPCODE, "bad opcode");
        assertEquals(VmException.Code.INVALID_OPCODE, e.code());
        assertEquals("bad opcode", e.getMessage());
        assertEquals(-1, e.ip());
    }

    @Test
    void constructorWithIp() {
        VmException e = new VmException(VmException.Code.MEMORY_FAULT, "bad access", 0x100);
        assertEquals(0x100, e.ip());
    }

    @Test
    void constructorWithCause() {
        RuntimeException cause = new RuntimeException("root");
        VmException e = new VmException(VmException.Code.HOST_ERROR, "host failed", cause);
        assertSame(cause, e.getCause());
    }

    @Test
    void staticFactoryMethods() {
        assertEquals(VmException.Code.INVALID_OPCODE, VmException.invalidOpcode(0xFF, 10).code());
        assertEquals("Invalid opcode 0xFF", VmException.invalidOpcode(0xFF, 10).getMessage());
        assertEquals(10, VmException.invalidOpcode(0xFF, 10).ip());

        assertEquals(VmException.Code.INVALID_OPERAND, VmException.invalidOperand("bad reg", 20).code());
        assertEquals(20, VmException.invalidOperand("bad reg", 20).ip());

        assertEquals(VmException.Code.MEMORY_FAULT, VmException.memoryFault("oob", 30).code());
        assertEquals(VmException.Code.ARITHMETIC_FAULT, VmException.arithmeticFault("div0", 40).code());
        assertEquals(VmException.Code.PRIVILEGE_FAULT, VmException.privilegeFault("no perms", 50).code());
        assertEquals(VmException.Code.HOST_ERROR, VmException.hostError("host fail", 60).code());
        assertEquals(VmException.Code.OUT_OF_FUEL, VmException.outOfFuel(70).code());
        assertEquals(VmException.Code.STACK_LIMIT, VmException.stackLimit(80).code());
        assertEquals(VmException.Code.CANCELLED, VmException.cancelled(90).code());
        assertEquals(VmException.Code.INVALID_JUMP, VmException.invalidJump(0xFFFF, 100).code());
        assertEquals(VmException.Code.DIVISION_BY_ZERO, VmException.divisionByZero(110).code());
        assertEquals(VmException.Code.INVALID_SHIFT_COUNT, VmException.invalidShiftCount(16, 120).code());
        assertEquals(VmException.Code.INVALID_REGION, VmException.invalidRegion(999, 130).code());
        assertEquals(VmException.Code.REGION_PERMISSION, VmException.regionPermission(5, "WRITE", 140).code());
        assertEquals(VmException.Code.REGION_BOUNDS, VmException.regionBounds(5, 100, 50, 150).code());
        assertEquals(VmException.Code.HOST_SERVICE_NOT_FOUND, VmException.hostServiceNotFound(0x1234, 160).code());
        assertEquals(VmException.Code.HOST_SERVICE_DENIED, VmException.hostServiceDenied(0x5678, 170).code());
        assertEquals(VmException.Code.HOST_BUFFER_TOO_SMALL, VmException.hostBufferTooSmall(0x1234, 100, 50, 180).code());
        assertEquals(VmException.Code.HOST_INVALID_ARGUMENT, VmException.hostInvalidArgument(0x1234, "bad arg", 190).code());
    }

    @Test
    void exceptionMessagesContainDetails() {
        assertTrue(VmException.invalidOpcode(0xAB, 1).getMessage().contains("AB"));
        assertTrue(VmException.invalidOperand("R9", 2).getMessage().contains("R9"));
        assertTrue(VmException.memoryFault("region 5", 3).getMessage().contains("region 5"));
        assertTrue(VmException.arithmeticFault("overflow", 4).getMessage().contains("overflow"));
        assertTrue(VmException.privilegeFault("region 3", 5).getMessage().contains("region 3"));
        assertTrue(VmException.hostError("service failed", 6).getMessage().contains("service failed"));
        assertTrue(VmException.invalidJump(0xFFFF, 7).getMessage().contains("FFFF"));
        assertTrue(VmException.divisionByZero(8).getMessage().contains("Division by zero"));
        assertTrue(VmException.invalidShiftCount(20, 9).getMessage().contains("20"));
        assertTrue(VmException.invalidRegion(7, 10).getMessage().contains("7"));
        assertTrue(VmException.regionPermission(3, "READ", 11).getMessage().contains("3") && VmException.regionPermission(3, "READ", 11).getMessage().contains("READ"));
        assertTrue(VmException.regionBounds(2, 50, 30, 12).getMessage().contains("2") && VmException.regionBounds(2, 50, 30, 12).getMessage().contains("50") && VmException.regionBounds(2, 50, 30, 12).getMessage().contains("30"));
    }
}
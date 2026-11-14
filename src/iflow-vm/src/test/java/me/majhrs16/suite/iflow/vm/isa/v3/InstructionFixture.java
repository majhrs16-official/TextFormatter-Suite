package me.majhrs16.suite.iflow.vm.isa.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class InstructionFixture {

    private InstructionFixture() {
    }

    static byte[] instruction(int family, int operation, int form, int rd, int rs,
                              int base, int idx, int size, int immediate) {
        int length = form == 0 ? 3 : 7;
        byte[] bytes = new byte[length];
        bytes[0] = (byte) ((family << 6) | (operation << 2) | form);
        int descriptor = (rd << 12) | (rs << 8) | (base << 4) | (idx << 3) | (size << 1);
        bytes[1] = (byte) descriptor;
        bytes[2] = (byte) (descriptor >>> 8);
        if (length == 7) {
            put32(bytes, 3, immediate);
        }
        return bytes;
    }

    static Instruction decode(byte[] bytes) {
        return new InstructionDecoder().decode(0, address -> bytes[address] & 0xFF);
    }

    static void assertIllegal(byte[] bytes) {
        MachineFault fault = assertThrows(MachineFault.class, () -> decode(bytes));
        assertEquals(MachineFault.Code.ILLEGAL_INSTRUCTION, fault.code());
    }

    static void put32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }
}

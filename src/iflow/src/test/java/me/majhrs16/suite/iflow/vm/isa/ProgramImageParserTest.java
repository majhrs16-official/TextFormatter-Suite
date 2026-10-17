package me.majhrs16.suite.iflow.vm.isa;

import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural validation performed before hash verification. These cases only need
 * a well-formed 16-byte header: the size checks fire before the code/constants
 * payload is read or the hash is checked.
 */
class ProgramImageParserTest {

    private static byte[] header(int entryPoint, int codeLength, int constantsLength) {
        return new byte[]{
            1, 1, 1, 1, // format, isa, minAbi, minFirmware
            (byte) (entryPoint >> 8), (byte) entryPoint,
            (byte) (codeLength >> 8), (byte) codeLength,
            (byte) (constantsLength >> 8), (byte) constantsLength,
            0,          // capabilities length
            0, 0, 0, 0, 0 // padding
        };
    }

    @Test
    void rejectsCodeLargerThanTheCodeRegion() {
        byte[] data = header(0, VmMemoryMap.CODE_MAX_SIZE + 1, 0);

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("Code section too large"), e.getMessage());
    }

    @Test
    void rejectsConstantsLargerThanTheConstantsPage() {
        // codeLength = 1 so the parser reads one code byte before the constants
        // size check fires (which happens before truncation/hash validation).
        byte[] data = new byte[17];
        byte[] h = header(0, 1, VmMemoryMap.CONSTANTS_MAX_SIZE / 2 + 1);
        System.arraycopy(h, 0, data, 0, h.length);

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("Constants section too large"), e.getMessage());
    }
}

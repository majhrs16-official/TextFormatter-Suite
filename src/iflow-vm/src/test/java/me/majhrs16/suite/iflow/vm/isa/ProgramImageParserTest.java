package me.majhrs16.suite.iflow.vm.isa;

import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural validation performed before hash verification. These cases only need
 * a well-formed 20-byte header (format 2): the size checks fire before the
 * code/constants payload is read or the hash is checked.
 */
class ProgramImageParserTest {

    /**
     * Format-2 header: magic + versions + sizes + reserved bytes. No debug section
     * and no capabilities, so the parser reaches the size checks with a header the
     * magic/version validation accepts.
     */
    private static byte[] header(int entryPoint, int codeLength, int constantsLength) {
        return new byte[]{
            0x46, 0x4C, 0x4F, 0x57,           // magic "FLOW"
            2, 2, 2, 1,                       // format, isa, minAbi, minFirmware
            (byte) (entryPoint >> 8), (byte) entryPoint,
            (byte) (codeLength >> 8), (byte) codeLength,
            (byte) (constantsLength >> 8), (byte) constantsLength,
            0,                                // capabilities length
            0,                                // reserved
            0, 0,                             // debug length
            0, 0                              // reserved
        };
    }

    /** A complete, hash-correct image with the given code and debug text. */
    private static byte[] image(byte[] code, String debug) throws Exception {
        byte[] dbg = debug.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x46, 0x4C, 0x4F, 0x57});
        out.write(2); out.write(2); out.write(2); out.write(1);
        out.write(0); out.write(0);                 // entry point 0
        out.write((code.length >> 8) & 0xFF); out.write(code.length & 0xFF);
        out.write(0); out.write(0);                 // constants length
        out.write(0);                               // capabilities count
        out.write(0);                               // reserved
        out.write((dbg.length >> 8) & 0xFF); out.write(dbg.length & 0xFF);
        out.write(0); out.write(0);                 // reserved
        out.write(code, 0, code.length);
        out.write(dbg, 0, dbg.length);
        byte[] body = out.toByteArray();
        out.write(MessageDigest.getInstance("SHA-256").digest(body));
        return out.toByteArray();
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
        byte[] data = new byte[21];
        byte[] h = header(0, 1, VmMemoryMap.CONSTANTS_MAX_SIZE / 2 + 1);
        System.arraycopy(h, 0, data, 0, h.length);

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("Constants section too large"), e.getMessage());
    }

    @Test
    void rejectsAnOlderAbiBinary() {
        // The runtime implements exactly one HOST calling convention: a program
        // built for the previous ABI must be rejected, not silently accepted.
        byte[] h = header(0, 1, 0);
        h[6] = 1; // minAbi = 1 (current is 2)

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(h));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("Incompatible ABI version"), e.getMessage());
    }

    @Test
    void rejectsANewerAbiBinary() {
        byte[] h = header(0, 1, 0);
        h[6] = 3; // minAbi = 3 (current is 2)

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(h));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("Incompatible ABI version"), e.getMessage());
    }

    @Test
    void rejectsAFileWithoutTheMagicNumber() {
        // A foreign file must fail as "not a .flow", before any version or hash
        // check (IFLOW-AUD-038).
        byte[] data = new byte[20];
        data[4] = 2; // a plausible format version, but no magic

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("bad magic"), e.getMessage());
    }

    @Test
    void readsTheOptionalDebugSection() throws Exception {
        // Format 2 carries the debug section before the hash; the parser must
        // surface it and still verify the hash over the whole payload.
        ProgramImage program = ProgramImageParser.parse(
            image(new byte[]{(byte) 0x68}, "0000: HALT\nSYM main = 0x0000\n"));

        assertEquals(1, program.code().length);
        assertTrue(program.debugInfo().contains("HALT"), program.debugInfo());
        assertTrue(program.debugInfo().contains("SYM main"), program.debugInfo());
    }

    @Test
    void aProgramWithoutDebugSectionHasEmptyDebugInfo() throws Exception {
        ProgramImage program = ProgramImageParser.parse(image(new byte[]{(byte) 0x68}, ""));
        assertEquals("", program.debugInfo());
    }
}

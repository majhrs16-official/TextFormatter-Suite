package me.majhrs16.suite.iflow.vm.isa;

import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural validation performed before hash verification: header fields,
 * TOC bounds, segment rules and the format-2 layout revision.
 */
class ProgramImageParserTest {

    private static final int HEADER_SIZE = 32;
    private static final int TOC_ENTRY_SIZE = 16;

    private record SegmentSpec(ProgramSegmentType type, int flags, int virtualAddress,
                               byte[] data, int memSize) {
    }

    private static SegmentSpec program(byte[] data) {
        return new SegmentSpec(ProgramSegmentType.PROGRAM,
            ProgramSegment.FLAG_READ | ProgramSegment.FLAG_EXECUTE | ProgramSegment.FLAG_USER,
            0x1000, data, data.length);
    }

    private static SegmentSpec debug(String text) {
        return new SegmentSpec(ProgramSegmentType.DEBUG, 0, 0,
            text.getBytes(StandardCharsets.UTF_8), 0);
    }

    /**
     * Serializes a complete ROM2 image with the given TOC entries and payloads.
     * The trailing SHA-256 covers the whole body, so every case starts from a
     * hash-correct file and only the rule under test is violated.
     */
    private static byte[] image(int entryPoint, SegmentSpec... segments) throws Exception {
        int tocOffset = HEADER_SIZE;
        int payloadStart = tocOffset + segments.length * TOC_ENTRY_SIZE;
        int payloadSize = payloadStart;
        for (SegmentSpec segment : segments) {
            payloadSize += segment.data().length;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x46, 0x4C, 0x4F, 0x57}); // magic "FLOW"
        out.write(2); // format
        out.write(2); // ISA
        out.write(2); // minimum ABI
        out.write(1); // minimum firmware
        out.write(entryPoint & 0xFF);
        out.write((entryPoint >> 8) & 0xFF);
        out.write(tocOffset & 0xFF);
        out.write((tocOffset >> 8) & 0xFF);
        out.write(segments.length & 0xFF);
        out.write((segments.length >> 8) & 0xFF);
        out.write(0); out.write(0); // header flags
        out.write(new byte[]{0x52, 0x4F, 0x4D, 0x32}); // layout "ROM2"
        out.write(payloadSize & 0xFF);
        out.write((payloadSize >>> 8) & 0xFF);
        out.write((payloadSize >>> 16) & 0xFF);
        out.write((payloadSize >>> 24) & 0xFF);
        out.write(new byte[8]); // reserved

        int cursor = payloadStart;
        for (SegmentSpec segment : segments) {
            out.write(segment.type().code());
            out.write(segment.flags());
            out.write(segment.virtualAddress() & 0xFF);
            out.write((segment.virtualAddress() >> 8) & 0xFF);
            out.write(cursor & 0xFF);
            out.write((cursor >>> 8) & 0xFF);
            out.write((cursor >>> 16) & 0xFF);
            out.write((cursor >>> 24) & 0xFF);
            out.write(segment.data().length & 0xFF);
            out.write((segment.data().length >> 8) & 0xFF);
            out.write(segment.memSize() & 0xFF);
            out.write((segment.memSize() >> 8) & 0xFF);
            out.write(new byte[4]); // reserved
            cursor += segment.data().length;
        }
        for (SegmentSpec segment : segments) {
            out.write(segment.data());
        }

        byte[] body = out.toByteArray();
        out.write(MessageDigest.getInstance("SHA-256").digest(body));
        return out.toByteArray();
    }

    @Test
    void rejectsProgramOutsideTheCodeRegion() throws Exception {
        byte[] data = image(0x1000, new SegmentSpec(ProgramSegmentType.PROGRAM,
            ProgramSegment.FLAG_READ | ProgramSegment.FLAG_EXECUTE | ProgramSegment.FLAG_USER,
            0x1000, new byte[]{0x68}, 0x2001));

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("outside the code region"), e.getMessage());
    }

    @Test
    void rejectsProgramInTheConstantsPage() throws Exception {
        SegmentSpec segment = new SegmentSpec(ProgramSegmentType.PROGRAM,
            ProgramSegment.FLAG_READ | ProgramSegment.FLAG_EXECUTE | ProgramSegment.FLAG_USER,
            VmMemoryMap.CONSTANTS_BASE, new byte[]{0x68}, 1);
        byte[] data = image(VmMemoryMap.CONSTANTS_BASE, segment);

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("outside the code region"), e.getMessage());
    }

    @Test
    void acceptsProgramThatEndsAtTheCodeLimit() throws Exception {
        byte[] code = new byte[VmMemoryMap.PAGE];
        code[0] = 0x68;
        SegmentSpec segment = new SegmentSpec(ProgramSegmentType.PROGRAM,
            ProgramSegment.FLAG_READ | ProgramSegment.FLAG_EXECUTE | ProgramSegment.FLAG_USER,
            0x1000, code, code.length);

        ProgramImage program = ProgramImageParser.parse(image(0x1000, segment));
        assertEquals(VmMemoryMap.CONSTANTS_BASE, program.code().length);
    }

    @Test
    void rejectsConstantsOutsideTheConstantsPage() throws Exception {
        byte[] data = image(0x1000,
            program(new byte[]{0x68}),
            new SegmentSpec(ProgramSegmentType.CONSTANTS, ProgramSegment.FLAG_READ,
                VmMemoryMap.CONSTANTS_BASE, new byte[]{0, 0},
                VmMemoryMap.CONSTANTS_MAX_SIZE + 1));

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("outside the image region"), e.getMessage());
    }

    @Test
    void rejectsAnOlderAbiBinary() throws Exception {
        // The runtime implements exactly one HOST calling convention: a program
        // built for the previous ABI must be rejected, not silently accepted.
        byte[] data = image(0x1000, program(new byte[]{0x68}));
        data[6] = 1; // minAbi = 1 (current is 2)

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("Incompatible ABI version"), e.getMessage());
    }

    @Test
    void rejectsANewerAbiBinary() throws Exception {
        byte[] data = image(0x1000, program(new byte[]{0x68}));
        data[6] = 3; // minAbi = 3 (current is 2)

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("Incompatible ABI version"), e.getMessage());
    }

    @Test
    void rejectsAFileWithoutTheMagicNumber() {
        // A foreign file must fail as "not a .flow", before any version or hash
        // check (IFLOW-AUD-038).
        byte[] data = new byte[64];
        data[4] = 2; // a plausible format version, but no magic

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("bad magic"), e.getMessage());
    }

    @Test
    void rejectsThePreviousLayoutWithoutADualReader() throws Exception {
        // The 20-byte header of the former layout is not a supported variant:
        // layoutMagic must reject it instead of falling back to another reader.
        byte[] data = image(0x1000, program(new byte[]{0x68}));
        data[16] = 0; data[17] = 0; data[18] = 0; data[19] = 0;

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("Unsupported .flow layout"), e.getMessage());
    }

    @Test
    void readsLittleEndianHeaderAndTocFields() throws Exception {
        // 0x1000 is written as 00 10 in little-endian; a big-endian reader
        // would decode 0x0010 and reject or misplace the segment.
        byte[] data = image(0x1000, new SegmentSpec(ProgramSegmentType.PROGRAM,
            ProgramSegment.FLAG_READ | ProgramSegment.FLAG_EXECUTE | ProgramSegment.FLAG_USER,
            0x1000, new byte[]{0x68}, 0x1000));

        ProgramImage program = ProgramImageParser.parse(data);
        ProgramSegment segment = program.segments()[0];

        assertEquals(0x1000, program.entryPoint());
        assertEquals(0x1000, segment.virtualAddress());
        assertEquals(0x1000, segment.memSize());
        assertEquals(1, segment.size());
    }

    @Test
    void rejectsBigEndianPayloadSizeWithoutADualReader() throws Exception {
        // The former layout wrote payloadSize big-endian. That is a different
        // format, not a variant: the little-endian reader rejects it instead of
        // guessing the byte order.
        byte[] data = image(0x1000, program(new byte[]{0x68}));
        int payloadSize = data.length - 32;
        data[20] = (byte) ((payloadSize >>> 24) & 0xFF);
        data[21] = (byte) ((payloadSize >>> 16) & 0xFF);
        data[22] = (byte) ((payloadSize >>> 8) & 0xFF);
        data[23] = (byte) (payloadSize & 0xFF);

        VmException e = assertThrows(VmException.class, () -> ProgramImageParser.parse(data));
        assertEquals(VmException.Code.INVALID_PROGRAM, e.code());
        assertTrue(e.getMessage().contains("Payload size mismatch"), e.getMessage());
    }

    @Test
    void readsTheOptionalDebugSection() throws Exception {
        // Format 2 carries the debug section as a TOC segment; the parser must
        // surface it and still verify the hash over the whole payload.
        ProgramImage program = ProgramImageParser.parse(
            image(0x1000, program(new byte[]{0x68}), debug("0000: HALT\nSYM main = 0x1000\n")));

        assertEquals(0x1001, program.code().length);
        assertEquals(0x68, program.code()[0x1000] & 0xFF);
        assertTrue(program.debugInfo().contains("HALT"), program.debugInfo());
        assertTrue(program.debugInfo().contains("SYM main"), program.debugInfo());
    }

    @Test
    void aProgramWithoutDebugSectionHasEmptyDebugInfo() throws Exception {
        ProgramImage program = ProgramImageParser.parse(
            image(0x1000, program(new byte[]{0x68})));
        assertEquals("", program.debugInfo());
    }
}

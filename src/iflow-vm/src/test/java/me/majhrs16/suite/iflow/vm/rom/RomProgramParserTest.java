package me.majhrs16.suite.iflow.vm.rom;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RomProgramParserTest {

    @Test
    void rejectsBadPrg3Magic() {
        mutateProgram(rom -> rom[RomFixture.programOffset(rom)] = 'X');
    }

    @Test
    void rejectsPrg3ReservedField() {
        mutateProgram(rom -> RomFixture.putU32(rom, RomFixture.programOffset(rom) + 28, 1));
    }

    @Test
    void rejectsCodeOffsetBelowHeader() {
        mutateProgram(rom -> RomFixture.putU32(rom, RomFixture.programOffset(rom) + 4, 31));
    }

    @Test
    void rejectsCodeEndBeforeStart() {
        mutateProgram(rom -> RomFixture.putU32(rom, RomFixture.programOffset(rom) + 8, 32));
    }

    @Test
    void rejectsCodeOutsideSegment() {
        mutateProgram(rom -> RomFixture.putU32(rom, RomFixture.programOffset(rom) + 8, 100));
    }

    @Test
    void rejectsDataEndBeforeStart() {
        mutateProgram(rom -> {
            int offset = RomFixture.programOffset(rom);
            RomFixture.putU32(rom, offset + 12, 36);
            RomFixture.putU32(rom, offset + 16, 32);
        });
    }

    @Test
    void rejectsDataOverlappingCode() {
        mutateProgram(rom -> {
            int offset = RomFixture.programOffset(rom);
            RomFixture.putU32(rom, offset + 12, 32);
            RomFixture.putU32(rom, offset + 16, 36);
        });
    }

    @Test
    void rejectsDataInPrg3Header() {
        mutateProgram(rom -> {
            int offset = RomFixture.programOffset(rom);
            RomFixture.putU32(rom, offset + 12, 0);
            RomFixture.putU32(rom, offset + 16, 4);
        });
    }

    @Test
    void rejectsCapabilityRangeOutsideSegment() {
        mutateProgram(rom -> {
            int offset = RomFixture.programOffset(rom);
            RomFixture.putU32(rom, offset + 20, 32);
            RomFixture.putU32(rom, offset + 24, 100);
        });
    }

    @Test
    void rejectsCapabilityOverlappingCode() {
        byte[] rom = programRom(List.of("iflow.property.read"));
        int offset = RomFixture.programOffset(rom);
        int capabilitiesOffset = (int) RomFixture.u32(rom, offset + 20);
        RomFixture.putU32(rom, offset + 20, 32);
        RomFixture.putU32(rom, offset + 24,
                RomFixture.u32(rom, offset + 24) + capabilitiesOffset - 32L);
        RomFixture.fixAllHashes(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsUnknownCapability() {
        assertInvalid(programRom(List.of("iflow.unknown")));
    }

    @Test
    void rejectsTooManyCapabilities() {
        List<String> capabilities = new ArrayList<>();
        for (int i = 0; i < 257; i++) {
            capabilities.add("iflow.property.read");
        }
        assertInvalid(programRom(capabilities));
    }

    @Test
    void rejectsCapabilityTooLong() {
        byte[] rom = programRom(List.of("iflow.property.read"));
        int offset = RomFixture.programOffset(rom);
        int capabilitiesOffset = (int) RomFixture.u32(rom, offset + 20);
        RomFixture.putU16(rom, offset + capabilitiesOffset + 2, 129);
        RomFixture.fixAllHashes(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsInvalidUtf8Capability() {
        byte[] rom = programRom(List.of("abcd"));
        int offset = RomFixture.programOffset(rom);
        int capabilitiesOffset = (int) RomFixture.u32(rom, offset + 20);
        rom[offset + capabilitiesOffset + 4] = (byte) 0xC3;
        rom[offset + capabilitiesOffset + 5] = (byte) 0x28;
        RomFixture.fixAllHashes(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsNulCapability() {
        byte[] rom = programRom(List.of("abcd"));
        int offset = RomFixture.programOffset(rom);
        int capabilitiesOffset = (int) RomFixture.u32(rom, offset + 20);
        rom[offset + capabilitiesOffset + 4] = 0;
        RomFixture.fixAllHashes(rom);
        assertInvalid(rom);
    }

    @Test
    void acceptsProgramDataAndCapabilities() {
        byte[] rom = RomFixture.standard()
                .remove(RomSegmentType.PROGRAM)
                .program(new byte[]{1, 2, 3, 4}, new byte[]{5, 6, 7, 8},
                        List.of("iflow.property.read"), RomFixture.USER_BASE, RomFixture.PAGE)
                .build();

        RomImage image = RomParser.parse(rom);
        RomProgram program = image.programs().get(0);

        assertEquals(32, program.header().codeOffset());
        assertEquals(36, program.header().codeEnd());
        assertEquals(36, program.header().dataOffset());
        assertEquals(40, program.header().dataEnd());
        assertEquals(List.of("iflow.property.read"), program.capabilities());
    }

    private static byte[] programRom(List<String> capabilities) {
        return RomFixture.standard()
                .remove(RomSegmentType.PROGRAM)
                .program(new byte[]{0, 0, 0, 0}, new byte[0], capabilities,
                        RomFixture.USER_BASE, RomFixture.PAGE)
                .build();
    }

    private static void mutateProgram(java.util.function.Consumer<byte[]> mutation) {
        byte[] rom = RomFixture.minimal();
        mutation.accept(rom);
        RomFixture.fixAllHashes(rom);
        assertInvalid(rom);
    }

    private static void assertInvalid(byte[] rom) {
        assertThrows(InvalidRomException.class, () -> RomParser.parse(rom));
    }
}

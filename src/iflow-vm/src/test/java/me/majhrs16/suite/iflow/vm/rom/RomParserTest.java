package me.majhrs16.suite.iflow.vm.rom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RomParserTest {

    @Test
    void parsesMinimalRom() {
        RomImage image = RomParser.parse(RomFixture.minimal());

        assertEquals(3, image.header().formatVersion());
        assertEquals(32, image.header().isaVersion());
        assertEquals(3, image.header().abiVersion());
        assertEquals(4, image.segments().size());
        assertEquals(1, image.programs().size());
        assertTrue(image.segment(RomSegmentType.BIOS).isPresent());
        assertTrue(image.segment(RomSegmentType.BOOT).isPresent());
        assertTrue(image.segment(RomSegmentType.KERNEL).isPresent());
        assertEquals(Prg3Header.HEADER_SIZE, image.programs().get(0).header().codeOffset());
        assertTrue(image.programs().get(0).capabilities().isEmpty());
        assertEquals(image.header().romSize(), image.bytes().length);
    }

    @Test
    void rejectsV2MagicExplicitly() {
        byte[] v2 = new byte[80];
        v2[0] = 'F';
        v2[1] = 'L';
        v2[2] = 'O';
        v2[3] = 'W';

        UnsupportedFormatException exception = assertThrows(
                UnsupportedFormatException.class,
                () -> RomParser.parse(v2)
        );
        assertTrue(exception.getMessage().contains("v2"));
    }

    @Test
    void rejectsForeignMagic() {
        assertThrows(UnsupportedFormatException.class, () -> RomParser.parse(new byte[80]));
    }

    @Test
    void rejectsTruncatedHeader() {
        assertThrows(InvalidRomException.class,
                () -> RomParser.parse(Arrays.copyOf(RomFixture.minimal(), 40)));
    }

    @Test
    void rejectsUnsupportedVersionAndSizeFields() {
        assertUnsupported(rom -> RomFixture.putU16(rom, 8, 2));
        assertUnsupported(rom -> RomFixture.putU16(rom, 10, 16));
        assertUnsupported(rom -> RomFixture.putU16(rom, 12, 2));
        assertUnsupported(rom -> RomFixture.putU16(rom, 14, 79));
        assertUnsupported(rom -> RomFixture.putU16(rom, 34, 63));
        assertUnsupported(rom -> RomFixture.putU32(rom, 36, 2));
    }

    @Test
    void rejectsReservedHeaderFlags() {
        assertInvalid(rom -> RomFixture.putU32(rom, 16, 2));
    }

    @Test
    void rejectsRomLargerThanLimit() {
        byte[] rom = RomFixture.minimal();
        RomFixture.putU32(rom, 20, RomParser.MAX_ROM_SIZE + 1L);
        assertInvalid(rom);
    }

    @Test
    void rejectsRomSizeLargerThanData() {
        byte[] rom = RomFixture.minimal();
        RomFixture.putU32(rom, 20, rom.length + 1L);
        assertInvalid(rom);
    }

    @Test
    void rejectsTruncatedRom() {
        byte[] rom = Arrays.copyOf(RomFixture.minimal(), RomFixture.minimal().length - 1);
        assertInvalid(rom);
    }

    @Test
    void rejectsGlobalHashMismatch() {
        byte[] rom = RomFixture.minimal();
        rom[RomFixture.programOffset(rom)] ^= 1;
        assertInvalid(rom);
    }

    @Test
    void rejectsZeroGlobalHash() {
        byte[] rom = RomFixture.minimal();
        Arrays.fill(rom, 48, 80, (byte) 0);
        assertInvalid(rom);
    }

    @Test
    void rejectsMisalignedTocOffset() {
        assertInvalid(rom -> RomFixture.putU32(rom, 28, 81));
    }

    @Test
    void rejectsTocOutsideRom() {
        assertInvalid(rom -> RomFixture.putU32(rom, 28, rom.length));
    }

    @Test
    void rejectsTooManyTocEntries() {
        assertInvalid(rom -> RomFixture.putU16(rom, 32, 257));
    }

    @Test
    void rejectsMissingRequiredSegments() {
        assertInvalid(RomFixture.standard().remove(RomSegmentType.BIOS).build());
        assertInvalid(RomFixture.standard().remove(RomSegmentType.BOOT).build());
        assertInvalid(RomFixture.standard().remove(RomSegmentType.KERNEL).build());
    }

    @Test
    void rejectsDuplicateBios() {
        assertInvalid(RomFixture.standard()
                .bios(new byte[]{1, 2, 3, 4}, RomFixture.PAGE)
                .build());
    }

    @Test
    void rejectsMoreThan64Programs() {
        RomFixture.Builder builder = RomFixture.standard();
        for (int i = 1; i <= 64; i++) {
            builder.program(new byte[]{0, 0, 0, 0},
                    RomFixture.USER_BASE + i * RomFixture.PAGE,
                    RomFixture.PAGE);
        }
        assertInvalid(builder.build());
    }

    @Test
    void rejectsRomSegmentOverlap() {
        byte[] rom = RomFixture.minimal();
        int bios = RomFixture.tocIndex(rom, RomSegmentType.BIOS);
        int boot = RomFixture.tocIndex(rom, RomSegmentType.BOOT);
        RomFixture.putU32(rom, RomFixture.tocEntryOffset(rom, boot) + 4,
                RomFixture.u32(rom, RomFixture.tocEntryOffset(rom, bios) + 4));
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsVirtualSegmentOverlap() {
        byte[] rom = RomFixture.minimal();
        int boot = RomFixture.tocIndex(rom, RomSegmentType.BOOT);
        int entry = RomFixture.tocEntryOffset(rom, boot);
        RomFixture.putU32(rom, entry + 12, RomFixture.KERNEL_LOAD);
        RomFixture.putU32(rom, entry + 16, RomFixture.KERNEL_LOAD);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsMisalignedLoadAddress() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.PROGRAM));
        RomFixture.putU32(rom, entry + 12, RomFixture.USER_BASE + 1);
        RomFixture.putU32(rom, entry + 16, RomFixture.USER_BASE + 33);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsMemSizeSmallerThanSize() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.PROGRAM));
        int size = (int) RomFixture.u32(rom, entry + 8);
        RomFixture.putU32(rom, entry + 20, size - 1);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsEntryPointOutsideProgramCode() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.PROGRAM));
        RomFixture.putU32(rom, entry + 16, RomFixture.USER_BASE + 1);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsResetVectorOutsideBios() {
        assertInvalid(rom -> RomFixture.putU32(rom, 24, RomFixture.PAGE));
    }

    @Test
    void rejectsWritableExecutableSegment() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.KERNEL));
        rom[entry + 1] = (byte) (RomFixture.R | RomFixture.W | RomFixture.X);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsUserAccessibleBios() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.BIOS));
        rom[entry + 1] = (byte) (RomFixture.R | RomFixture.X | RomFixture.U);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsProgramWithoutUserFlag() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.PROGRAM));
        rom[entry + 1] = (byte) (RomFixture.R | RomFixture.X);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsWritableProgram() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.PROGRAM));
        rom[entry + 1] = (byte) (RomFixture.R | RomFixture.W | RomFixture.X | RomFixture.U);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsUserAccessibleBoot() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.BOOT));
        rom[entry + 1] = (byte) (RomFixture.R | RomFixture.X | RomFixture.U);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsNonExecutableSegmentWithEntryPoint() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.KERNEL));
        rom[entry + 1] = (byte) (RomFixture.R | RomFixture.W);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void acceptsWritableKernelWithZeroEntryPoint() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.KERNEL));
        rom[entry + 1] = (byte) (RomFixture.R | RomFixture.W);
        RomFixture.putU32(rom, entry + 16, 0);
        RomFixture.fixGlobalHash(rom);

        RomImage image = RomParser.parse(rom);
        TocEntry kernel = image.segment(RomSegmentType.KERNEL).orElseThrow().entry();

        assertTrue(kernel.writable());
        assertFalse(kernel.executable());
        assertEquals(0, kernel.entryPoint());
    }

    @Test
    void rejectsReservedTocFlags() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.BOOT));
        rom[entry + 1] = (byte) (RomFixture.R | RomFixture.X | 0x10);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsNonZeroTocReservedFields() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.BOOT));
        RomFixture.putU16(rom, entry + 2, 1);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);

        rom = RomFixture.minimal();
        entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.BOOT));
        rom[entry + 56] = 1;
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsZeroSegmentHash() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.BOOT));
        Arrays.fill(rom, entry + 24, entry + 56, (byte) 0);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsBiosOutsideZero() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.BIOS));
        RomFixture.putU32(rom, entry + 12, 0x1000);
        RomFixture.putU32(rom, entry + 16, 0x1000);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsSegmentInsideTocArea() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.BOOT));
        RomFixture.putU32(rom, entry + 4, 80);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsDataInUserSpace() {
        assertInvalid(RomFixture.standard()
                .segment(RomSegmentType.DATA.code(), RomFixture.R,
                        RomFixture.USER_BASE + RomFixture.PAGE, 0,
                        RomFixture.PAGE, new byte[]{1, 2, 3, 4})
                .build());
    }

    @Test
    void rejectsExecutableData() {
        assertInvalid(RomFixture.standard()
                .segment(RomSegmentType.DATA.code(), RomFixture.R | RomFixture.X,
                        RomFixture.KERNEL_BASE + 2 * RomFixture.PAGE, 0,
                        RomFixture.PAGE, new byte[]{1, 2, 3, 4})
                .build());
    }

    @Test
    void rejectsFontInMmio() {
        assertInvalid(RomFixture.standard()
                .segment(RomSegmentType.FONT.code(), RomFixture.R,
                        0x0100_0000, 0, RomFixture.PAGE, new byte[]{1, 2, 3, 4})
                .build());
    }

    @Test
    void rejectsLoadAddressOverflow() {
        byte[] rom = RomFixture.minimal();
        int entry = RomFixture.tocEntryOffset(rom, RomFixture.tocIndex(rom, RomSegmentType.KERNEL));
        RomFixture.putU32(rom, entry + 12, 0xFFFFF000L);
        RomFixture.putU32(rom, entry + 16, 0xFFFFF000L);
        RomFixture.putU32(rom, entry + 20, 0x2000);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsSegmentHashMismatch() {
        byte[] rom = RomFixture.minimal();
        rom[RomFixture.programOffset(rom)] ^= 1;
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void rejectsSignedFlagWithoutSignature() {
        assertInvalid(rom -> RomFixture.putU32(rom, 16, 1));
    }

    @Test
    void rejectsUnsignedWithSignatureFields() {
        assertInvalid(rom -> {
            RomFixture.putU32(rom, 40, rom.length - 4);
            RomFixture.putU32(rom, 44, 4);
        });
    }

    @Test
    void acceptsSignedRom() {
        byte[] rom = RomFixture.standard().signed(new byte[]{1, 2, 3, 4}).build();
        RomImage image = RomParser.parse(rom);

        assertTrue(image.header().signedImage());
        assertEquals(4, image.header().signatureSize());
    }

    @Test
    void rejectsSignatureOverlappingSegment() {
        byte[] rom = RomFixture.standard().signed(new byte[]{1, 2, 3, 4}).build();
        RomFixture.putU32(rom, 40, RomFixture.programOffset(rom));
        RomFixture.putU32(rom, 44, 4);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    @Test
    void ignoresBytesAfterRomSize() {
        byte[] minimal = RomFixture.minimal();
        byte[] padded = Arrays.copyOf(minimal, minimal.length + 16);
        padded[minimal.length] = 0x7F;

        RomImage image = RomParser.parse(padded);

        assertEquals(minimal.length, image.header().romSize());
        assertEquals(minimal.length, image.bytes().length);
    }

    @Test
    void acceptsKnownCapabilities() {
        byte[] rom = RomFixture.standard()
                .remove(RomSegmentType.PROGRAM)
                .program(new byte[]{0, 0, 0, 0}, new byte[0], List.of(
                        "iflow.property.read",
                        "sync.surface",
                        "sync.discord.channel.create",
                        "module.textformatter.channel.set"
                ), RomFixture.USER_BASE, RomFixture.PAGE)
                .build();

        RomImage image = RomParser.parse(rom);

        assertEquals(4, image.programs().get(0).capabilities().size());
    }

    private static void assertUnsupported(Consumer<byte[]> mutation) {
        byte[] rom = RomFixture.minimal();
        mutation.accept(rom);
        RomFixture.fixGlobalHash(rom);
        assertThrows(UnsupportedFormatException.class, () -> RomParser.parse(rom));
    }

    private static void assertInvalid(Consumer<byte[]> mutation) {
        byte[] rom = RomFixture.minimal();
        mutation.accept(rom);
        RomFixture.fixGlobalHash(rom);
        assertInvalid(rom);
    }

    private static void assertInvalid(byte[] rom) {
        assertThrows(InvalidRomException.class, () -> RomParser.parse(rom));
    }
}

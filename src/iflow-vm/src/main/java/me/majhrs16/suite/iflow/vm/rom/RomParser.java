package me.majhrs16.suite.iflow.vm.rom;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parser for the v3 {@code FLOWROM} container described by ADR-037 §8.
 *
 * <p>This class only validates and exposes the ROM. It does not map memory,
 * create processes, or read a v2 {@code .flow}: a v2 artifact is rejected
 * explicitly with {@link UnsupportedFormatException}.</p>
 */
public final class RomParser {

    static final int HASH_SIZE = 32;
    static final int HEADER_SIZE = 80;
    static final int TOC_ENTRY_SIZE = 64;
    static final int FORMAT_VERSION = 3;
    static final int ISA_VERSION = 32;
    static final int ABI_VERSION = 3;
    static final int HASH_ALGORITHM_SHA256 = 1;
    static final int MAX_ROM_SIZE = 16 * 1024 * 1024;
    static final int MAX_TOC_ENTRIES = 256;
    static final int MAX_PROGRAMS = 64;
    static final int PAGE_SIZE = 4096;

    static final long ADDRESS_SPACE_END = 0x1_0000_0000L;
    static final long ROM_END = 0x0100_0000L;
    static final long USER_BASE = 0x0200_0000L;
    static final long USER_END = 0x8000_0000L;
    static final long KERNEL_BASE = 0x8000_0000L;

    private static final int HASH_OFFSET = 48;
    private static final byte[] MAGIC = {'F', 'L', 'O', 'W', 'R', 'O', 'M', 0};
    private static final byte[] V2_MAGIC = {'F', 'L', 'O', 'W'};
    private static final byte[] ZERO_HASH = new byte[HASH_SIZE];

    private RomParser() {
    }

    public static RomImage parse(byte[] data) {
        if (data == null) {
            throw new InvalidRomException("ROM data is null");
        }
        if (data.length < MAGIC.length) {
            rejectV2IfPresent(data);
            throw new InvalidRomException("ROM is truncated before its magic");
        }
        if (!matches(data, 0, MAGIC)) {
            rejectV2IfPresent(data);
            throw new UnsupportedFormatException("not a FLOWROM image");
        }
        if (data.length < HEADER_SIZE) {
            throw new InvalidRomException("ROM header is truncated");
        }

        int formatVersion = u16(data, 8);
        int isaVersion = u16(data, 10);
        int abiVersion = u16(data, 12);
        int headerSize = u16(data, 14);
        int flags = (int) u32(data, 16);
        long romSizeLong = u32(data, 20);
        int resetVector = (int) u32(data, 24);
        long tocOffsetLong = u32(data, 28);
        int tocCount = u16(data, 32);
        int tocEntrySize = u16(data, 34);
        long hashAlgorithm = u32(data, 36);
        long signatureOffsetLong = u32(data, 40);
        long signatureSizeLong = u32(data, 44);

        if (formatVersion != FORMAT_VERSION) {
            throw new UnsupportedFormatException("unsupported formatVersion " + formatVersion);
        }
        if (isaVersion != ISA_VERSION) {
            throw new UnsupportedFormatException("unsupported isaVersion " + isaVersion);
        }
        if (abiVersion != ABI_VERSION) {
            throw new UnsupportedFormatException("unsupported abiVersion " + abiVersion);
        }
        if (headerSize != HEADER_SIZE) {
            throw new UnsupportedFormatException("unsupported headerSize " + headerSize);
        }
        if ((flags & 0xFFFF_FFFE) != 0) {
            throw new InvalidRomException("header flags contain reserved bits");
        }
        if (romSizeLong > MAX_ROM_SIZE) {
            throw new InvalidRomException("romSize exceeds 16 MiB");
        }
        if (romSizeLong < HEADER_SIZE) {
            throw new InvalidRomException("romSize is smaller than the header");
        }
        int romSize = (int) romSizeLong;
        if (data.length < romSize) {
            throw new InvalidRomException("ROM data is shorter than romSize");
        }
        if (tocOffsetLong < HEADER_SIZE || (tocOffsetLong & 7) != 0) {
            throw new InvalidRomException("tocOffset must be at least 80 and 8-byte aligned");
        }
        if (tocCount > MAX_TOC_ENTRIES) {
            throw new InvalidRomException("tocCount exceeds " + MAX_TOC_ENTRIES);
        }
        long tocEnd = tocOffsetLong + (long) tocCount * TOC_ENTRY_SIZE;
        if (tocOffsetLong > romSize || tocEnd > romSize) {
            throw new InvalidRomException("TOC does not fit in the ROM");
        }
        long minimumSegmentOffset = HEADER_SIZE + (long) tocCount * TOC_ENTRY_SIZE;
        if (tocEntrySize != TOC_ENTRY_SIZE) {
            throw new UnsupportedFormatException("unsupported tocEntrySize " + tocEntrySize);
        }
        if (hashAlgorithm != HASH_ALGORITHM_SHA256) {
            throw new UnsupportedFormatException("unsupported hashAlgorithm " + hashAlgorithm);
        }

        boolean signed = (flags & 1) != 0;
        if (signed) {
            if (signatureSizeLong == 0) {
                throw new InvalidRomException("signed ROM has no signature");
            }
            if (signatureOffsetLong < HEADER_SIZE
                    || signatureOffsetLong + signatureSizeLong > romSize) {
                throw new InvalidRomException("signature does not fit in the ROM");
            }
        } else if (signatureOffsetLong != 0 || signatureSizeLong != 0) {
            throw new InvalidRomException("unsigned ROM has signature fields");
        }

        byte[] headerHash = Arrays.copyOfRange(data, HASH_OFFSET, HASH_OFFSET + HASH_SIZE);
        if (isZero(headerHash)) {
            throw new InvalidRomException("ROM header hash is all zero");
        }
        if (!MessageDigest.isEqual(headerHash, globalHash(data, romSize))) {
            throw new InvalidRomException("ROM header hash mismatch");
        }

        List<TocEntry> entries = new ArrayList<>(tocCount);
        for (int i = 0; i < tocCount; i++) {
            int base = (int) tocOffsetLong + i * TOC_ENTRY_SIZE;
            int typeCode = data[base] & 0xFF;
            RomSegmentType type;
            try {
                type = RomSegmentType.fromCode(typeCode);
            } catch (IllegalArgumentException exception) {
                throw new InvalidRomException("unknown TOC segment type " + typeCode);
            }
            int segmentFlags = data[base + 1] & 0xFF;
            if ((segmentFlags & 0xF0) != 0) {
                throw new InvalidRomException("TOC flags contain reserved bits");
            }
            if (u16(data, base + 2) != 0) {
                throw new InvalidRomException("TOC reserved field is non-zero");
            }

            long offset = u32(data, base + 4);
            long size = u32(data, base + 8);
            long loadAddress = u32(data, base + 12);
            long entryPoint = u32(data, base + 16);
            long memSize = u32(data, base + 20);

            if (size == 0) {
                throw new InvalidRomException("TOC segment has size 0");
            }
            if (offset < minimumSegmentOffset || offset + size > romSize) {
                throw new InvalidRomException("TOC segment does not fit in the ROM");
            }
            if (overlaps(offset, offset + size, tocOffsetLong, tocEnd)) {
                throw new InvalidRomException("TOC segment overlaps the TOC");
            }
            if (memSize < size) {
                throw new InvalidRomException("TOC memSize is smaller than size");
            }
            if ((loadAddress & (PAGE_SIZE - 1)) != 0) {
                throw new InvalidRomException("TOC loadAddress is not 4 KiB aligned");
            }
            if (loadAddress + memSize > ADDRESS_SPACE_END) {
                throw new InvalidRomException("TOC loadAddress + memSize overflows 32 bits");
            }
            byte[] segmentHash = Arrays.copyOfRange(data, base + 24, base + 24 + HASH_SIZE);
            if (isZero(segmentHash)) {
                throw new InvalidRomException("TOC segment hash is all zero");
            }
            if (!isZero(data, base + 56, 8)) {
                throw new InvalidRomException("TOC trailing reserved field is non-zero");
            }

            entries.add(new TocEntry(
                    type,
                    segmentFlags,
                    (int) offset,
                    (int) size,
                    (int) loadAddress,
                    (int) entryPoint,
                    (int) memSize,
                    segmentHash
            ));
        }

        int biosCount = 0;
        int bootCount = 0;
        int kernelCount = 0;
        int programCount = 0;
        for (TocEntry entry : entries) {
            switch (entry.type()) {
                case BIOS -> biosCount++;
                case BOOT -> bootCount++;
                case KERNEL -> kernelCount++;
                case PROGRAM -> programCount++;
                default -> {
                }
            }
        }
        if (biosCount != 1 || bootCount != 1 || kernelCount != 1) {
            throw new InvalidRomException("ROM must contain exactly one BIOS, BOOT and KERNEL");
        }
        if (programCount > MAX_PROGRAMS) {
            throw new InvalidRomException("ROM contains more than " + MAX_PROGRAMS + " programs");
        }

        for (TocEntry entry : entries) {
            RomTocEntryValidator.validate(entry);
        }
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                TocEntry first = entries.get(i);
                TocEntry second = entries.get(j);
                if (overlaps(u(first.offset()), u(first.offset()) + u(first.size()),
                        u(second.offset()), u(second.offset()) + u(second.size()))) {
                    throw new InvalidRomException("ROM segments overlap");
                }
                if (overlaps(u(first.loadAddress()), u(first.loadAddress()) + u(first.memSize()),
                        u(second.loadAddress()), u(second.loadAddress()) + u(second.memSize()))) {
                    throw new InvalidRomException("virtual segments overlap");
                }
            }
        }

        if (signed) {
            long signatureStart = signatureOffsetLong;
            long signatureEnd = signatureStart + signatureSizeLong;
            if (overlaps(signatureStart, signatureEnd, 0, HEADER_SIZE)
                    || overlaps(signatureStart, signatureEnd, tocOffsetLong, tocEnd)) {
                throw new InvalidRomException("signature overlaps the header or TOC");
            }
            for (TocEntry entry : entries) {
                if (overlaps(signatureStart, signatureEnd,
                        u(entry.offset()), u(entry.offset()) + u(entry.size()))) {
                    throw new InvalidRomException("signature overlaps a segment");
                }
            }
        }

        List<RomSegment> segments = new ArrayList<>(entries.size());
        for (TocEntry entry : entries) {
            byte[] segmentData = Arrays.copyOfRange(data, entry.offset(), entry.offset() + entry.size());
            if (!MessageDigest.isEqual(entry.hash(), sha256(segmentData))) {
                throw new InvalidRomException("segment hash mismatch for " + entry.type());
            }
            segments.add(new RomSegment(entry, segmentData));
        }

        List<RomProgram> programs = new ArrayList<>(programCount);
        TocEntry bios = null;
        for (int i = 0; i < entries.size(); i++) {
            TocEntry entry = entries.get(i);
            if (entry.type() == RomSegmentType.BIOS) {
                bios = entry;
            }
            if (entry.type() == RomSegmentType.PROGRAM) {
                programs.add(RomProgramParser.parse(segments.get(i)));
            }
        }

        if (!bios.executable()) {
            throw new InvalidRomException("BIOS segment is not executable");
        }
        long reset = u(resetVector);
        long biosLoad = u(bios.loadAddress());
        if (reset < biosLoad || reset >= biosLoad + u(bios.size())) {
            throw new InvalidRomException("resetVector is outside the BIOS segment");
        }

        RomHeader header = new RomHeader(
                formatVersion,
                isaVersion,
                abiVersion,
                headerSize,
                flags,
                romSize,
                resetVector,
                (int) tocOffsetLong,
                tocCount,
                tocEntrySize,
                (int) hashAlgorithm,
                (int) signatureOffsetLong,
                (int) signatureSizeLong,
                headerHash
        );
        return new RomImage(header, segments, programs, Arrays.copyOf(data, romSize));
    }

    private static byte[] globalHash(byte[] data, int romSize) {
        MessageDigest digest = digest();
        digest.update(data, 0, HASH_OFFSET);
        digest.update(ZERO_HASH);
        if (romSize > HEADER_SIZE) {
            digest.update(data, HEADER_SIZE, romSize - HEADER_SIZE);
        }
        return digest.digest();
    }

    private static byte[] sha256(byte[] data) {
        MessageDigest digest = digest();
        digest.update(data);
        return digest.digest();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void rejectV2IfPresent(byte[] data) {
        if (startsWith(data, V2_MAGIC)) {
            throw new UnsupportedFormatException("v2 .flow is not supported; rebuild as FLOWROM v3");
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return matches(data, 0, prefix);
    }

    private static boolean matches(byte[] data, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isZero(byte[] data) {
        return isZero(data, 0, data.length);
    }

    private static boolean isZero(byte[] data, int offset, int length) {
        for (int i = 0; i < length; i++) {
            if (data[offset + i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlaps(long aStart, long aEnd, long bStart, long bEnd) {
        if (aStart >= aEnd || bStart >= bEnd) {
            return false;
        }
        return aStart < bEnd && bStart < aEnd;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24);
    }

    private static long u(int value) {
        return Integer.toUnsignedLong(value);
    }
}

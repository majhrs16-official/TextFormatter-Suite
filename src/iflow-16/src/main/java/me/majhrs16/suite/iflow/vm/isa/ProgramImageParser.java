package me.majhrs16.suite.iflow.vm.isa;

import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the 16-bit {@code .flow} ROM container (format 2, layout revision 1).
 *
 * <h2>Header (32 bytes, little-endian)</h2>
 * <pre>
 *   +0   u32  magic 0x464C4F57 ("FLOW")
 *   +4   u8   formatVersion (2)
 *   +5   u8   isaVersion (2)
 *   +6   u8   minAbiVersion (2)
 *   +7   u8   minFirmwareVersion (1)
 *   +8   u16  entryPoint (absolute virtual address)
 *   +10  u16  tocOffset (absolute file offset)
 *   +12  u16  tocEntryCount
 *   +14  u16  headerFlags (0)
 *   +16  u32  layoutMagic 0x524F4D32 ("ROM2")
 *   +20  u32  payloadSize (bytes before the trailing SHA-256)
 *   +24  u32  reserved (0)
 *   +28  u32  reserved (0)
 * </pre>
 *
 * <h2>TOC entry (16 bytes, little-endian)</h2>
 * <pre>
 *   +0   u8   segment type
 *   +1   u8   flags (1=R, 2=W, 4=X, 8=user)
 *   +2   u16  virtualAddress
 *   +4   u32  fileOffset
 *   +8   u16  size (stored bytes)
 *   +10  u16  memSize (reserved bytes; >= size for loadable segments)
 *   +12  u32  reserved (0)
 * </pre>
 *
 * <p>
 * The layout magic is deliberately distinct from the old v2 container: the
 * previous format used bytes 16..19 for {@code debugLength + reserved}, so an
 * old file cannot accidentally satisfy this header. There is exactly one reader
 * and no legacy branch.
 * </p>
 */
public final class ProgramImageParser {

    /** Magic bytes at the start of every .flow file: ASCII "FLOW". */
    private static final byte[] MAGIC = {'F', 'L', 'O', 'W'};

    /** Marks format 2 layout revision 1 (header + TOC + typed segments). */
    private static final byte[] LAYOUT_MAGIC = {'R', 'O', 'M', '2'};

    public static final int HEADER_SIZE = 32;
    public static final int TOC_ENTRY_SIZE = 16;
    public static final int MAX_TOC_ENTRIES = 16;
    public static final int HASH_SIZE = 32;

    public static final int CURRENT_FORMAT_VERSION = ProgramImage.CURRENT_FORMAT_VERSION;
    public static final int CURRENT_ISA_VERSION = ProgramImage.CURRENT_ISA_VERSION;
    public static final int CURRENT_ABI_VERSION = ProgramImage.CURRENT_ABI_VERSION;
    public static final int CURRENT_FIRMWARE_VERSION = ProgramImage.CURRENT_FIRMWARE_VERSION;

    /** Loadable segments must stay below the runtime data region. */
    private static final int IMAGE_END = VmMemoryMap.DATA_BASE; // 0x3000

    private ProgramImageParser() {
    }

    public static ProgramImage parse(byte[] data) {
        if (data == null) {
            throw invalid("Program image is null");
        }
        if (data.length < HEADER_SIZE + HASH_SIZE) {
            throw invalid("Data too short for header and hash");
        }

        if (!matches(data, 0, MAGIC)) {
            throw invalid("Not a .flow file (bad magic: 0x"
                + Integer.toHexString(u32(data, 0))
                + ", expected ASCII FLOW)");
        }

        int formatVersion = u8(data, 4);
        int isaVersion = u8(data, 5);
        int minAbiVersion = u8(data, 6);
        int minFirmwareVersion = u8(data, 7);
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw invalid("Unsupported format version: " + formatVersion
                + " (expected " + CURRENT_FORMAT_VERSION + ")");
        }
        if (isaVersion > CURRENT_ISA_VERSION) {
            throw invalid("ISA version too new: " + isaVersion
                + " (max " + CURRENT_ISA_VERSION + ")");
        }
        if (minAbiVersion != CURRENT_ABI_VERSION) {
            throw invalid("Incompatible ABI version: program requires ABI " + minAbiVersion
                + ", runtime implements " + CURRENT_ABI_VERSION);
        }
        if (minFirmwareVersion > CURRENT_FIRMWARE_VERSION) {
            throw invalid("Minimum firmware version too new: " + minFirmwareVersion
                + " (max " + CURRENT_FIRMWARE_VERSION + ")");
        }

        if (!matches(data, 16, LAYOUT_MAGIC)) {
            throw invalid("Unsupported .flow layout: expected ROM2, got 0x"
                + Integer.toHexString(u32(data, 16)));
        }

        int entryPoint = u16(data, 8);
        int tocOffset = u16(data, 10);
        int tocEntryCount = u16(data, 12);
        int headerFlags = u16(data, 14);
        long payloadSize = u32u(data, 20);
        int reserved24 = u32(data, 24);
        int reserved28 = u32(data, 28);

        if (headerFlags != 0) {
            throw invalid("Unsupported header flags: 0x" + Integer.toHexString(headerFlags));
        }
        if (reserved24 != 0 || reserved28 != 0) {
            throw invalid("Reserved header fields must be zero");
        }
        if (payloadSize != data.length - HASH_SIZE) {
            throw invalid("Payload size mismatch: header says " + payloadSize
                + ", file has " + (data.length - HASH_SIZE));
        }
        if (tocEntryCount < 1 || tocEntryCount > MAX_TOC_ENTRIES) {
            throw invalid("Invalid TOC entry count: " + tocEntryCount
                + " (allowed 1.." + MAX_TOC_ENTRIES + ")");
        }
        if (tocOffset < HEADER_SIZE) {
            throw invalid("TOC offset overlaps the header: " + tocOffset);
        }
        long tocEnd = (long) tocOffset + (long) tocEntryCount * TOC_ENTRY_SIZE;
        if (tocEnd > payloadSize) {
            throw invalid("TOC extends past the payload: " + tocEnd + " > " + payloadSize);
        }

        List<TocEntry> toc = new ArrayList<>(tocEntryCount);
        for (int i = 0; i < tocEntryCount; i++) {
            int base = tocOffset + i * TOC_ENTRY_SIZE;
            int typeCode = u8(data, base);
            ProgramSegmentType type = ProgramSegmentType.fromCode(typeCode);
            if (type == null) {
                throw invalid("Unknown segment type: 0x" + Integer.toHexString(typeCode));
            }
            int flags = u8(data, base + 1);
            int virtualAddress = u16(data, base + 2);
            int fileOffset = u32(data, base + 4);
            int size = u16(data, base + 8);
            int memSize = u16(data, base + 10);
            int reserved12 = u32(data, base + 12);
            if ((flags & ~ProgramSegment.FLAG_MASK) != 0) {
                throw invalid("Unknown segment flags: 0x" + Integer.toHexString(flags)
                    + " in TOC entry " + i);
            }
            if (fileOffset < 0) {
                throw invalid("Segment " + i + " has an invalid file offset: 0x"
                    + Integer.toHexString(fileOffset));
            }
            if (reserved12 != 0) {
                throw invalid("Reserved TOC fields must be zero in entry " + i);
            }
            if (fileOffset < tocEnd) {
                throw invalid("Segment " + i + " overlaps the header/TOC: file offset "
                    + fileOffset + " < " + tocEnd);
            }
            if ((long) fileOffset + size > payloadSize) {
                throw invalid("Segment " + i + " extends past the payload: "
                    + ((long) fileOffset + size) + " > " + payloadSize);
            }
            toc.add(new TocEntry(i, type, flags, virtualAddress, fileOffset, size, memSize));
        }

        rejectOverlaps(toc);
        validateSegments(toc);
        validateEntryPoint(entryPoint, toc);
        verifyHash(data, (int) payloadSize);

        List<ProgramSegment> segments = new ArrayList<>(toc.size());
        List<String> capabilities = new ArrayList<>();
        String debugInfo = "";
        boolean seenCapabilities = false;
        boolean seenDebug = false;

        for (TocEntry entry : toc) {
            byte[] bytes = new byte[entry.size];
            System.arraycopy(data, entry.fileOffset, bytes, 0, entry.size);
            if (entry.type == ProgramSegmentType.CAPABILITIES) {
                if (seenCapabilities) {
                    throw invalid("Duplicate CAPABILITIES segment");
                }
                seenCapabilities = true;
                capabilities.addAll(parseCapabilities(bytes));
                continue;
            }
            if (entry.type == ProgramSegmentType.DEBUG) {
                if (seenDebug) {
                    throw invalid("Duplicate DEBUG segment");
                }
                seenDebug = true;
                debugInfo = new String(bytes, StandardCharsets.UTF_8);
                continue;
            }
            segments.add(new ProgramSegment(entry.type, entry.virtualAddress,
                bytes, entry.memSize, entry.flags));
        }

        return ProgramImage.builder()
            .formatVersion(formatVersion)
            .isaVersion(isaVersion)
            .minAbiVersion(minAbiVersion)
            .minFirmwareVersion(minFirmwareVersion)
            .entryPoint(entryPoint)
            .segments(segments.toArray(new ProgramSegment[0]))
            .capabilities(capabilities.toArray(new String[0]))
            .debugInfo(debugInfo)
            .hash(copyHash(data, (int) payloadSize))
            .build();
    }

    private static void rejectOverlaps(List<TocEntry> toc) {
        for (int i = 0; i < toc.size(); i++) {
            TocEntry a = toc.get(i);
            if (a.size == 0) {
                continue;
            }
            for (int j = i + 1; j < toc.size(); j++) {
                TocEntry b = toc.get(j);
                if (b.size == 0) {
                    continue;
                }
                if (rangesOverlap(a.fileOffset, a.fileOffset + a.size,
                        b.fileOffset, b.fileOffset + b.size)) {
                    throw invalid("File ranges overlap between TOC entries "
                        + a.index + " and " + b.index);
                }
            }
        }

        for (int i = 0; i < toc.size(); i++) {
            TocEntry a = toc.get(i);
            if (!a.type.isLoadable()) {
                continue;
            }
            for (int j = i + 1; j < toc.size(); j++) {
                TocEntry b = toc.get(j);
                if (!b.type.isLoadable()) {
                    continue;
                }
                if (rangesOverlap(a.virtualAddress, a.virtualAddress + a.memSize,
                        b.virtualAddress, b.virtualAddress + b.memSize)) {
                    throw invalid("Virtual ranges overlap between TOC entries "
                        + a.index + " and " + b.index);
                }
            }
        }
    }

    private static void validateSegments(List<TocEntry> toc) {
        boolean seenProgram = false;
        boolean seenBios = false;
        boolean seenBootloader = false;
        boolean seenKernel = false;
        boolean seenConstants = false;
        boolean seenCapabilities = false;
        boolean seenDebug = false;

        for (TocEntry entry : toc) {
            if (!entry.type.isLoadable()) {
                if (entry.virtualAddress != 0 || entry.memSize != 0 || entry.flags != 0) {
                    throw invalid(entry.type + " segment must not reserve memory or set flags");
                }
                if (entry.size == 0) {
                    throw invalid(entry.type + " segment cannot be empty");
                }
                if (entry.type == ProgramSegmentType.CAPABILITIES) {
                    if (seenCapabilities) throw invalid("Duplicate CAPABILITIES segment");
                    seenCapabilities = true;
                } else if (entry.type == ProgramSegmentType.DEBUG) {
                    if (seenDebug) throw invalid("Duplicate DEBUG segment");
                    seenDebug = true;
                }
                continue;
            }

            if ((entry.virtualAddress & (VmMemoryMap.PAGE - 1)) != 0) {
                throw invalid(entry.type + " segment is not page-aligned: 0x"
                    + Integer.toHexString(entry.virtualAddress));
            }
            if (entry.size == 0) {
                throw invalid(entry.type + " segment cannot be empty");
            }
            if (entry.memSize < entry.size) {
                throw invalid(entry.type + " memSize " + entry.memSize
                    + " is smaller than its stored size " + entry.size);
            }
            long end = (long) entry.virtualAddress + entry.memSize;
            boolean constants = entry.type == ProgramSegmentType.CONSTANTS;
            long limit = constants ? IMAGE_END : VmMemoryMap.CODE_MAX_SIZE;
            if (end > limit) {
                throw invalid(entry.type + " segment outside the "
                    + (constants ? "image region" : "code region")
                    + ": end 0x" + Long.toHexString(end)
                    + " > 0x" + Integer.toHexString((int) limit));
            }

            switch (entry.type) {
                case BIOS -> {
                    if (seenBios) throw invalid("Duplicate BIOS segment");
                    seenBios = true;
                    if (entry.virtualAddress != 0) {
                        throw invalid("BIOS segment must start at 0x0000");
                    }
                    if (entry.memSize > VmMemoryMap.PAGE) {
                        throw invalid("BIOS segment exceeds page 0");
                    }
                    requireCodeFlags(entry);
                }
                case BOOTLOADER -> {
                    if (seenBootloader) throw invalid("Duplicate BOOTLOADER segment");
                    seenBootloader = true;
                    requireCodeFlags(entry);
                }
                case KERNEL -> {
                    if (seenKernel) throw invalid("Duplicate KERNEL segment");
                    seenKernel = true;
                    requireCodeFlags(entry);
                }
                case PROGRAM -> {
                    if (seenProgram) throw invalid("Duplicate PROGRAM segment");
                    seenProgram = true;
                    if ((entry.flags & (ProgramSegment.FLAG_READ | ProgramSegment.FLAG_EXECUTE
                            | ProgramSegment.FLAG_USER))
                            != (ProgramSegment.FLAG_READ | ProgramSegment.FLAG_EXECUTE
                            | ProgramSegment.FLAG_USER)
                        || (entry.flags & ProgramSegment.FLAG_WRITE) != 0) {
                        throw invalid("PROGRAM segment must be user-owned R/X (no W)");
                    }
                }
                case CONSTANTS -> {
                    if (seenConstants) throw invalid("Duplicate CONSTANTS segment");
                    seenConstants = true;
                    if (entry.virtualAddress != VmMemoryMap.CONSTANTS_BASE) {
                        throw invalid("CONSTANTS segment must start at 0x"
                            + Integer.toHexString(VmMemoryMap.CONSTANTS_BASE));
                    }
                    if (entry.memSize != entry.size) {
                        throw invalid("CONSTANTS segment cannot reserve extra memory");
                    }
                    if (entry.memSize > VmMemoryMap.CONSTANTS_MAX_SIZE) {
                        throw invalid("Constants section too large: " + entry.memSize
                            + " bytes, maximum is " + VmMemoryMap.CONSTANTS_MAX_SIZE);
                    }
                    if ((entry.size & 1) != 0) {
                        throw invalid("Constants section must contain whole u16 words");
                    }
                    if (entry.flags != ProgramSegment.FLAG_READ) {
                        throw invalid("CONSTANTS segment must be read-only");
                    }
                }
                default -> throw invalid("Unsupported loadable segment type: " + entry.type);
            }
        }

        if (!seenProgram) {
            throw invalid("Image has no PROGRAM segment");
        }
    }

    private static void requireCodeFlags(TocEntry entry) {
        int required = ProgramSegment.FLAG_READ | ProgramSegment.FLAG_EXECUTE;
        if ((entry.flags & required) != required
            || (entry.flags & ProgramSegment.FLAG_WRITE) != 0
            || (entry.flags & ProgramSegment.FLAG_USER) != 0) {
            throw invalid(entry.type + " segment must be kernel-owned R/X (no W, no user)");
        }
    }

    private static void validateEntryPoint(int entryPoint, List<TocEntry> toc) {
        for (TocEntry entry : toc) {
            if (!entry.type.isLoadable() || !entry.type.isExecutableType()) {
                continue;
            }
            if (entryPoint >= entry.virtualAddress
                && entryPoint < entry.virtualAddress + entry.size) {
                return;
            }
        }
        throw invalid("Entry point 0x" + Integer.toHexString(entryPoint)
            + " is outside every executable segment");
    }

    private static List<String> parseCapabilities(byte[] bytes) {
        List<String> capabilities = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != 0) {
                continue;
            }
            String capability = new String(bytes, start, i - start, StandardCharsets.UTF_8);
            if (capability.isEmpty()) {
                throw invalid("Empty capability string");
            }
            capabilities.add(capability);
            start = i + 1;
        }
        if (start != bytes.length) {
            throw invalid("Capability string is not NUL-terminated");
        }
        return capabilities;
    }

    private static byte[] copyHash(byte[] data, int payloadSize) {
        byte[] hash = new byte[HASH_SIZE];
        System.arraycopy(data, payloadSize, hash, 0, HASH_SIZE);
        return hash;
    }

    private static void verifyHash(byte[] data, int payloadSize) {
        byte[] expected = new byte[HASH_SIZE];
        System.arraycopy(data, payloadSize, expected, 0, HASH_SIZE);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data, 0, payloadSize);
            if (!MessageDigest.isEqual(digest.digest(), expected)) {
                throw invalid("Hash verification failed");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean rangesOverlap(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    private static int u8(byte[] data, int offset) {
        return data[offset] & 0xFF;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
            | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16)
            | ((data[offset + 3] & 0xFF) << 24);
    }

    private static boolean matches(byte[] data, int offset, byte[] expected) {
        for (int index = 0; index < expected.length; index++) {
            if (data[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static long u32u(byte[] data, int offset) {
        return u32(data, offset) & 0xFFFF_FFFFL;
    }

    private static VmException invalid(String message) {
        return new VmException(VmException.Code.INVALID_PROGRAM, message);
    }

    private static final class TocEntry {
        final int index;
        final ProgramSegmentType type;
        final int flags;
        final int virtualAddress;
        final int fileOffset;
        final int size;
        final int memSize;

        TocEntry(int index, ProgramSegmentType type, int flags, int virtualAddress,
                 int fileOffset, int size, int memSize) {
            this.index = index;
            this.type = type;
            this.flags = flags;
            this.virtualAddress = virtualAddress;
            this.fileOffset = fileOffset;
            this.size = size;
            this.memSize = memSize;
        }
    }
}

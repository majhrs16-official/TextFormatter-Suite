package me.majhrs16.suite.iflow.vm.rom;

import java.util.List;

/**
 * Decodes and validates the {@code PRG3} subheader and its capability blob.
 */
final class RomProgramParser {

    private RomProgramParser() {
    }

    static RomProgram parse(RomSegment segment) {
        byte[] data = segment.data();
        int size = data.length;
        if (size < Prg3Header.HEADER_SIZE) {
            throw new InvalidRomException("PROGRAM segment is smaller than PRG3");
        }
        if (!matches(data, 0, Prg3Header.MAGIC)) {
            throw new InvalidRomException("PROGRAM segment does not start with PRG3");
        }
        if (u32(data, 28) != 0) {
            throw new InvalidRomException("PRG3 reserved field is non-zero");
        }

        long codeOffset = u32(data, 4);
        long codeEnd = u32(data, 8);
        long dataOffset = u32(data, 12);
        long dataEnd = u32(data, 16);
        long capabilitiesOffset = u32(data, 20);
        long capabilitiesLength = u32(data, 24);

        if (codeOffset < Prg3Header.HEADER_SIZE || codeEnd <= codeOffset || codeEnd > size) {
            throw new InvalidRomException("invalid PRG3 code range");
        }

        boolean hasData = dataOffset != 0 || dataEnd != 0;
        if (dataEnd < dataOffset) {
            throw new InvalidRomException("PRG3 dataEnd is smaller than dataOffset");
        }
        if (hasData) {
            if (dataOffset < Prg3Header.HEADER_SIZE || dataEnd > size) {
                throw new InvalidRomException("PRG3 data range is outside the segment");
            }
        } else if (dataOffset != 0 || dataEnd != 0) {
            throw new InvalidRomException("PRG3 empty data range must be 0/0");
        }

        if (capabilitiesLength == 0) {
            if (capabilitiesOffset > size) {
                throw new InvalidRomException("PRG3 capability offset is outside the segment");
            }
        } else if (capabilitiesOffset < Prg3Header.HEADER_SIZE
                || capabilitiesOffset + capabilitiesLength > size) {
            throw new InvalidRomException("PRG3 capability range is outside the segment");
        }

        if (hasData && overlaps(codeOffset, codeEnd, dataOffset, dataEnd)) {
            throw new InvalidRomException("PRG3 code and data ranges overlap");
        }
        if (capabilitiesLength > 0) {
            long capabilitiesEnd = capabilitiesOffset + capabilitiesLength;
            if (overlaps(codeOffset, codeEnd, capabilitiesOffset, capabilitiesEnd)) {
                throw new InvalidRomException("PRG3 code and capability ranges overlap");
            }
            if (hasData && overlaps(dataOffset, dataEnd, capabilitiesOffset, capabilitiesEnd)) {
                throw new InvalidRomException("PRG3 data and capability ranges overlap");
            }
        }

        TocEntry entry = segment.entry();
        long entryOffset = Integer.toUnsignedLong(entry.entryPoint())
                - Integer.toUnsignedLong(entry.loadAddress());
        if (entryOffset < codeOffset || entryOffset >= codeEnd) {
            throw new InvalidRomException("PROGRAM entryPoint is outside the PRG3 code range");
        }
        if (Integer.toUnsignedLong(entry.memSize()) < dataEnd) {
            throw new InvalidRomException("PROGRAM memSize is smaller than dataEnd");
        }

        Prg3Header header = new Prg3Header(
                (int) codeOffset,
                (int) codeEnd,
                (int) dataOffset,
                (int) dataEnd,
                (int) capabilitiesOffset,
                (int) capabilitiesLength
        );
        List<String> capabilities = RomCapabilities.parse(
                data,
                header.capabilitiesOffset(),
                header.capabilitiesLength()
        );
        return new RomProgram(segment, header, capabilities);
    }

    private static boolean overlaps(long aStart, long aEnd, long bStart, long bEnd) {
        if (aStart >= aEnd || bStart >= bEnd) {
            return false;
        }
        return aStart < bEnd && bStart < aEnd;
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

    private static long u32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24);
    }
}

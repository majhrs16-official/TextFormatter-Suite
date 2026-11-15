package me.majhrs16.suite.iflow.vm.rom;

import java.util.Objects;

/**
 * One 64-byte TOC entry.
 *
 * @param type        segment type
 * @param flags       TOC permission flags: bit 0 R, bit 1 W, bit 2 X, bit 3 U
 * @param offset      segment offset in the ROM file
 * @param size        number of bytes copied from ROM
 * @param loadAddress virtual load address
 * @param entryPoint  execution entry, or 0 when the segment is not executable
 * @param memSize     virtual mapping size, at least {@code size}
 * @param hash        SHA-256 of the segment bytes
 */
public record TocEntry(
        RomSegmentType type,
        int flags,
        int offset,
        int size,
        int loadAddress,
        int entryPoint,
        int memSize,
        byte[] hash
) {

    public TocEntry {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(hash, "hash");
        if (hash.length != RomParser.HASH_SIZE) {
            throw new IllegalArgumentException("segment hash must be 32 bytes");
        }
        hash = hash.clone();
    }

    @Override
    public byte[] hash() {
        return hash.clone();
    }

    public boolean readable() {
        return (flags & 1) != 0;
    }

    public boolean writable() {
        return (flags & 2) != 0;
    }

    public boolean executable() {
        return (flags & 4) != 0;
    }

    public boolean userAccessible() {
        return (flags & 8) != 0;
    }
}

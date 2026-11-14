package me.majhrs16.suite.iflow.vm.rom;

import java.util.Objects;

/**
 * Decoded 80-byte v3 ROM header.
 *
 * @param formatVersion  format version, always 3 for a parsed image
 * @param isaVersion     ISA version, always 32 for a parsed image
 * @param abiVersion     {@code HOST} ABI version, always 3 for a parsed image
 * @param headerSize     size of this header, always 80
 * @param flags          bit 0 means "signed"; the remaining bits are reserved
 * @param romSize        total ROM size in bytes
 * @param resetVector    initial {@code PC}
 * @param tocOffset      ROM offset of the first TOC entry
 * @param tocCount       number of TOC entries
 * @param tocEntrySize   size of each TOC entry, always 64
 * @param hashAlgorithm  hash algorithm, always 1 (SHA-256)
 * @param signatureOffset offset of the optional signature, or 0
 * @param signatureSize   size of the optional signature, or 0
 * @param hash           SHA-256 of the ROM with the hash field zeroed
 */
public record RomHeader(
        int formatVersion,
        int isaVersion,
        int abiVersion,
        int headerSize,
        int flags,
        int romSize,
        int resetVector,
        int tocOffset,
        int tocCount,
        int tocEntrySize,
        int hashAlgorithm,
        int signatureOffset,
        int signatureSize,
        byte[] hash
) {

    public RomHeader {
        Objects.requireNonNull(hash, "hash");
        if (hash.length != RomParser.HASH_SIZE) {
            throw new IllegalArgumentException("ROM header hash must be 32 bytes");
        }
        hash = hash.clone();
    }

    @Override
    public byte[] hash() {
        return hash.clone();
    }

    public boolean signedImage() {
        return (flags & 1) != 0;
    }
}

package me.majhrs16.suite.iflow.vm.rom;

import java.util.Objects;

/**
 * A TOC entry together with the exact segment bytes copied from the ROM.
 */
public record RomSegment(TocEntry entry, byte[] data) {

    public RomSegment {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(data, "data");
        if (data.length != entry.size()) {
            throw new IllegalArgumentException("segment data length does not match TOC size");
        }
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}

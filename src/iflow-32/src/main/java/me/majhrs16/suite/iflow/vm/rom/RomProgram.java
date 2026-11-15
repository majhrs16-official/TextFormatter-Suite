package me.majhrs16.suite.iflow.vm.rom;

import java.util.List;
import java.util.Objects;

/**
 * Parsed {@code PROGRAM} segment: TOC entry, {@code PRG3} header and capabilities.
 */
public record RomProgram(
        RomSegment segment,
        Prg3Header header,
        List<String> capabilities
) {

    public RomProgram {
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(header, "header");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }

    public TocEntry entry() {
        return segment.entry();
    }
}

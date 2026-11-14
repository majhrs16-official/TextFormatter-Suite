package me.majhrs16.suite.iflow.vm.rom;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Validated v3 ROM image.
 *
 * <p>The constructor is package-private: only {@link RomParser} may create an
 * image, so an invalid header/TOC combination cannot be handed to the loader.</p>
 */
public final class RomImage {

    private final RomHeader header;
    private final List<RomSegment> segments;
    private final List<RomProgram> programs;
    private final byte[] bytes;

    RomImage(RomHeader header, List<RomSegment> segments, List<RomProgram> programs, byte[] bytes) {
        this.header = Objects.requireNonNull(header, "header");
        this.segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        this.programs = List.copyOf(Objects.requireNonNull(programs, "programs"));
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public RomHeader header() {
        return header;
    }

    public List<RomSegment> segments() {
        return segments;
    }

    public List<RomProgram> programs() {
        return programs;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public Optional<RomSegment> segment(RomSegmentType type) {
        for (RomSegment segment : segments) {
            if (segment.entry().type() == type) {
                return Optional.of(segment);
            }
        }
        return Optional.empty();
    }
}

package me.majhrs16.suite.iflow.vm.isa;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable ROM image: a TOC of typed segments plus metadata.
 * <p>
 * The image is the unit of deployment and versioning. Multiple executions share
 * the same {@code ProgramImage}. The authoritative representation is the
 * segment list; {@link #code()} and {@link #constants()} are derived views kept
 * for callers that inspect the executable image or the constant pool.
 * </p>
 */
public final class ProgramImage {

    // Format 2 layout revision 1 (2026-11-15): replaces the monolithic
    // header+code+constants container with a 32-byte header, a 16-byte TOC entry
    // per segment, and typed BIOS/PROGRAM/CONSTANTS/metadata sections.
    public static final int CURRENT_FORMAT_VERSION = 2;
    public static final int CURRENT_ISA_VERSION = 2;
    public static final int CURRENT_ABI_VERSION = 2;
    public static final int CURRENT_FIRMWARE_VERSION = 1;

    private final int formatVersion;
    private final int isaVersion;
    private final int minAbiVersion;
    private final int minFirmwareVersion;
    private final int entryPoint;
    private final ProgramSegment[] segments;
    private final byte[] code;
    private final int[] constants;
    private final String[] capabilities;
    private final byte[] hash; // SHA-256 of the payload (all bytes before the hash)
    private final String debugInfo;

    @JsonCreator
    public ProgramImage(
            @JsonProperty("formatVersion") int formatVersion,
            @JsonProperty("isaVersion") int isaVersion,
            @JsonProperty("minAbiVersion") int minAbiVersion,
            @JsonProperty("minFirmwareVersion") int minFirmwareVersion,
            @JsonProperty("entryPoint") int entryPoint,
            @JsonProperty("code") byte[] code,
            @JsonProperty("constants") int[] constants,
            @JsonProperty("capabilities") String[] capabilities,
            @JsonProperty("hash") byte[] hash,
            @JsonProperty("debugInfo") String debugInfo) {
        this(formatVersion, isaVersion, minAbiVersion, minFirmwareVersion, entryPoint,
            code, constants, capabilities, hash, debugInfo, null);
    }

    public ProgramImage(
            int formatVersion,
            int isaVersion,
            int minAbiVersion,
            int minFirmwareVersion,
            int entryPoint,
            byte[] code,
            int[] constants,
            String[] capabilities,
            byte[] hash,
            String debugInfo,
            ProgramSegment[] segments) {
        this.formatVersion = formatVersion;
        this.isaVersion = isaVersion;
        this.minAbiVersion = minAbiVersion;
        this.minFirmwareVersion = minFirmwareVersion;
        this.entryPoint = entryPoint;
        this.segments = normalizeSegments(code, constants, segments);
        this.code = buildCodeView(this.segments);
        this.constants = buildConstantsView(this.segments);
        this.capabilities = capabilities != null ? capabilities.clone() : new String[0];
        this.hash = hash != null ? hash.clone() : new byte[0];
        this.debugInfo = debugInfo != null ? debugInfo : "";
    }

    /**
     * Builds the authoritative segment list. A caller that only provides the
     * legacy {@code code}/{@code constants} views gets a single program segment
     * at 0x0000 and a constants segment at the canonical page; the loader always
     * consumes {@link #segments()}.
     */
    private static ProgramSegment[] normalizeSegments(byte[] code, int[] constants,
                                                       ProgramSegment[] segments) {
        if (segments != null && segments.length > 0) {
            return segments.clone();
        }
        List<ProgramSegment> result = new ArrayList<>();
        if (code != null && code.length > 0) {
            result.add(new ProgramSegment(
                ProgramSegmentType.PROGRAM, 0, code, code.length,
                ProgramSegment.FLAG_READ | ProgramSegment.FLAG_EXECUTE | ProgramSegment.FLAG_USER));
        }
        if (constants != null && constants.length > 0) {
            // Loadable segment payloads are copied to memory verbatim, and the
            // ISA reads words little-endian, so the constants view is encoded in
            // target byte order too.
            byte[] data = new byte[constants.length * 2];
            for (int i = 0; i < constants.length; i++) {
                data[i * 2] = (byte) (constants[i] & 0xFF);
                data[i * 2 + 1] = (byte) ((constants[i] >> 8) & 0xFF);
            }
            result.add(new ProgramSegment(
                ProgramSegmentType.CONSTANTS, VmMemoryMap.CONSTANTS_BASE,
                data, data.length, ProgramSegment.FLAG_READ));
        }
        return result.toArray(new ProgramSegment[0]);
    }

    /**
     * Derived executable view: bytes from 0x0000 to the highest executable
     * segment end, with gaps zero-filled. This preserves the old interpreter
     * invariant that {@code codeSize()} is an exclusive bound for control flow.
     */
    private static byte[] buildCodeView(ProgramSegment[] segments) {
        int end = 0;
        for (ProgramSegment segment : segments) {
            if (segment.isLoadable() && segment.isExecutable()) {
                end = Math.max(end, segment.endAddress());
            }
        }
        byte[] view = new byte[end];
        for (ProgramSegment segment : segments) {
            if (!segment.isLoadable() || !segment.isExecutable()) {
                continue;
            }
            byte[] data = segment.rawData();
            System.arraycopy(data, 0, view, segment.virtualAddress(), data.length);
        }
        return view;
    }

    private static int[] buildConstantsView(ProgramSegment[] segments) {
        List<ProgramSegment> constantsSegments = new ArrayList<>();
        for (ProgramSegment segment : segments) {
            if (segment.type() == ProgramSegmentType.CONSTANTS) {
                constantsSegments.add(segment);
            }
        }
        constantsSegments.sort(Comparator.comparingInt(ProgramSegment::virtualAddress));
        int words = 0;
        for (ProgramSegment segment : constantsSegments) {
            if ((segment.size() & 1) != 0) {
                throw new IllegalArgumentException(
                    "Constants segment has an odd number of bytes: " + segment.size());
            }
            words += segment.size() / 2;
        }
        int[] values = new int[words];
        int index = 0;
        for (ProgramSegment segment : constantsSegments) {
            byte[] data = segment.rawData();
            for (int i = 0; i < data.length; i += 2) {
                values[index++] = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8);
            }
        }
        return values;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int formatVersion() { return formatVersion; }
    public int isaVersion() { return isaVersion; }
    public int minAbiVersion() { return minAbiVersion; }
    public int minFirmwareVersion() { return minFirmwareVersion; }
    public int entryPoint() { return entryPoint; }
    public byte[] code() { return code.clone(); }
    public int[] constants() { return constants.clone(); }
    public String[] capabilities() { return capabilities.clone(); }
    public byte[] hash() { return hash.clone(); }
    public String debugInfo() { return debugInfo; }
    public ProgramSegment[] segments() { return segments.clone(); }

    public int codeSize() { return code.length; }
    public int constantsCount() { return constants.length; }

    public boolean hasCapability(String cap) {
        for (String c : capabilities) {
            if (c.equals(cap)) return true;
        }
        return false;
    }

    public static final class Builder {
        private int formatVersion = CURRENT_FORMAT_VERSION;
        private int isaVersion = CURRENT_ISA_VERSION;
        private int minAbiVersion = CURRENT_ABI_VERSION;
        private int minFirmwareVersion = CURRENT_FIRMWARE_VERSION;
        private int entryPoint = 0;
        private byte[] code = new byte[0];
        private int[] constants = new int[0];
        private String[] capabilities = new String[0];
        private byte[] hash = new byte[0];
        private String debugInfo = "";
        private ProgramSegment[] segments = new ProgramSegment[0];

        public Builder formatVersion(int v) { this.formatVersion = v; return this; }
        public Builder isaVersion(int v) { this.isaVersion = v; return this; }
        public Builder minAbiVersion(int v) { this.minAbiVersion = v; return this; }
        public Builder minFirmwareVersion(int v) { this.minFirmwareVersion = v; return this; }
        public Builder entryPoint(int v) { this.entryPoint = v; return this; }
        public Builder code(byte[] v) { this.code = v != null ? v.clone() : new byte[0]; return this; }
        public Builder constants(int[] v) { this.constants = v != null ? v.clone() : new int[0]; return this; }
        public Builder capabilities(String[] v) { this.capabilities = v != null ? v.clone() : new String[0]; return this; }
        public Builder hash(byte[] v) { this.hash = v != null ? v.clone() : new byte[0]; return this; }
        public Builder debugInfo(String v) { this.debugInfo = v; return this; }
        public Builder segments(ProgramSegment... v) {
            this.segments = v != null ? v.clone() : new ProgramSegment[0];
            return this;
        }

        public ProgramImage build() {
            return new ProgramImage(
                formatVersion, isaVersion, minAbiVersion, minFirmwareVersion,
                entryPoint, code, constants, capabilities, hash, debugInfo, segments
            );
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProgramImage that)) return false;
        return formatVersion == that.formatVersion
            && isaVersion == that.isaVersion
            && minAbiVersion == that.minAbiVersion
            && minFirmwareVersion == that.minFirmwareVersion
            && entryPoint == that.entryPoint
            && Arrays.equals(segments, that.segments)
            && Arrays.equals(code, that.code)
            && Arrays.equals(constants, that.constants)
            && Arrays.equals(capabilities, that.capabilities)
            && Arrays.equals(hash, that.hash)
            && Objects.equals(debugInfo, that.debugInfo);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(formatVersion, isaVersion, minAbiVersion,
            minFirmwareVersion, entryPoint, debugInfo);
        result = 31 * result + Arrays.hashCode(segments);
        result = 31 * result + Arrays.hashCode(code);
        result = 31 * result + Arrays.hashCode(constants);
        result = 31 * result + Arrays.hashCode(capabilities);
        result = 31 * result + Arrays.hashCode(hash);
        return result;
    }

    @Override
    public String toString() {
        return "ProgramImage{format=" + formatVersion + ", isa=" + isaVersion
            + ", abi>=" + minAbiVersion + ", fw>=" + minFirmwareVersion
            + ", entry=0x" + Integer.toHexString(entryPoint)
            + ", segments=" + segments.length + ", code=" + code.length + "B, consts="
            + constants.length + ", caps=" + capabilities.length + "}";
    }

    /**
     * Parses a .flow binary program image.
     */
    public static ProgramImage parse(byte[] data) {
        return ProgramImageParser.parse(data);
    }
}

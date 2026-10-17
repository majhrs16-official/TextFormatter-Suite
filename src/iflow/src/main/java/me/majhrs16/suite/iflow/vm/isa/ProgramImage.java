package me.majhrs16.suite.iflow.vm.isa;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable program image: bytecode + constants + metadata.
 * <p>
 * This is the unit of deployment and versioning. Multiple executions
 * share the same ProgramImage.
 * </p>
 */
public final class ProgramImage {

    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final int CURRENT_ISA_VERSION = 2;
    public static final int CURRENT_ABI_VERSION = 1;
    public static final int CURRENT_FIRMWARE_VERSION = 1;

    private final int formatVersion;
    private final int isaVersion;
    private final int minAbiVersion;
    private final int minFirmwareVersion;
    private final int entryPoint;
    private final byte[] code;
    private final int[] constants;
    private final String[] capabilities;
    private final byte[] hash; // SHA-256 of content
    private final String debugInfo; // Optional: symbols, source map

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
        this.formatVersion = formatVersion;
        this.isaVersion = isaVersion;
        this.minAbiVersion = minAbiVersion;
        this.minFirmwareVersion = minFirmwareVersion;
        this.entryPoint = entryPoint;
        this.code = code != null ? code.clone() : new byte[0];
        this.constants = constants != null ? constants.clone() : new int[0];
        this.capabilities = capabilities != null ? capabilities.clone() : new String[0];
        this.hash = hash != null ? hash.clone() : new byte[0];
        this.debugInfo = debugInfo;
    }

    // Builder for programmatic construction
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

        public ProgramImage build() {
            return new ProgramImage(
                formatVersion, isaVersion, minAbiVersion, minFirmwareVersion,
                entryPoint, code, constants, capabilities, hash, debugInfo
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
            + ", code=" + code.length + "B, consts=" + constants.length
            + ", caps=" + capabilities.length + "}";
    }

    /**
     * Parses a .flow binary program image.
     */
    public static ProgramImage parse(byte[] data) {
        return ProgramImageParser.parse(data);
    }
}
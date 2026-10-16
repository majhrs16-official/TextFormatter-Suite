package me.majhrs16.suite.iflow.vm.isa;

import me.majhrs16.suite.iflow.vm.isa.VmException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for .flow binary format.
 */
public final class ProgramImageParser {

    public static final int CURRENT_FORMAT_VERSION = ProgramImage.CURRENT_FORMAT_VERSION;
    public static final int CURRENT_ISA_VERSION = ProgramImage.CURRENT_ISA_VERSION;
    public static final int CURRENT_ABI_VERSION = ProgramImage.CURRENT_ABI_VERSION;
    public static final int CURRENT_FIRMWARE_VERSION = ProgramImage.CURRENT_FIRMWARE_VERSION;

    public static ProgramImage parse(byte[] data) {
        if (data.length < 16) {
            throw new VmException(VmException.Code.INVALID_PROGRAM, "Data too short for header");
        }

        int offset = 0;
        int formatVersion = data[offset++] & 0xFF;
        int isaVersion = data[offset++] & 0xFF;
        int minAbiVersion = data[offset++] & 0xFF;
        int minFirmwareVersion = data[offset++] & 0xFF;

        // Validate version compatibility
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "Unsupported format version: " + formatVersion + " (expected " + CURRENT_FORMAT_VERSION + ")");
        }
        if (isaVersion > CURRENT_ISA_VERSION) {
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "ISA version too new: " + isaVersion + " (max " + CURRENT_ISA_VERSION + ")");
        }
        if (minAbiVersion > CURRENT_ABI_VERSION) {
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "Minimum ABI version too new: " + minAbiVersion + " (max " + CURRENT_ABI_VERSION + ")");
        }
        if (minFirmwareVersion > CURRENT_FIRMWARE_VERSION) {
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "Minimum firmware version too new: " + minFirmwareVersion + " (max " + CURRENT_FIRMWARE_VERSION + ")");
        }

        int entryPoint = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        int codeLength = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        int constantsLength = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        int capabilitiesLength = data[offset++] & 0xFF;
        offset += 5; // padding

        // Validate code length
        if (codeLength < 0 || codeLength > 0xFFFF) {
            throw new VmException(VmException.Code.INVALID_PROGRAM, "Invalid code length: " + codeLength);
        }
        // Enforce 4KB code limit (1 page) to avoid overlapping with reserved regions
        final int MAX_CODE_SIZE = SoftMMU.PAGE_SIZE; // 4096 bytes
        if (codeLength > MAX_CODE_SIZE) {
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "Code section too large: " + codeLength + " bytes, maximum is " + MAX_CODE_SIZE + " bytes (1 page)");
        }

        // Read code
        if (offset + codeLength > data.length) {
            throw new VmException(VmException.Code.INVALID_PROGRAM, "Code section truncated");
        }
        byte[] code = new byte[codeLength];
        System.arraycopy(data, offset, code, 0, codeLength);
        offset += codeLength;

        // Read constants
        if (constantsLength < 0 || constantsLength > 0xFFFF) {
            throw new VmException(VmException.Code.INVALID_PROGRAM, "Invalid constants length: " + constantsLength);
        }
        if (offset + constantsLength * 2 > data.length) {
            throw new VmException(VmException.Code.INVALID_PROGRAM, "Constants section truncated");
        }
        int[] constants = new int[constantsLength];
        for (int i = 0; i < constantsLength; i++) {
            constants[i] = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        }

        // Read capabilities
        List<String> capabilities = new ArrayList<>();
        for (int i = 0; i < capabilitiesLength; i++) {
            if (offset >= data.length) {
                throw new VmException(VmException.Code.INVALID_PROGRAM, "Capabilities section truncated");
            }
            StringBuilder sb = new StringBuilder();
            int startOffset = offset;
            while (offset < data.length && data[offset] != 0) {
                sb.append((char) data[offset++]);
            }
            if (offset >= data.length) {
                throw new VmException(VmException.Code.INVALID_PROGRAM, "Capability string not null-terminated");
            }
            offset++; // skip null terminator
            String cap = sb.toString();
            if (cap.isEmpty()) {
                throw new VmException(VmException.Code.INVALID_PROGRAM, "Empty capability string");
            }
            capabilities.add(cap);
        }

        // Read hash (32 bytes)
        if (offset + 32 > data.length) {
            throw new VmException(VmException.Code.INVALID_PROGRAM, "Hash section truncated");
        }
        byte[] hash = new byte[32];
        System.arraycopy(data, offset, hash, 0, 32);
        offset += 32;

        // Verify no extra bytes
        if (offset != data.length) {
            throw new VmException(VmException.Code.INVALID_PROGRAM, "Extra bytes after hash: " + (data.length - offset));
        }

        // Verify hash integrity
        if (!verifyHash(data, offset - 32, hash)) {
            throw new VmException(VmException.Code.INVALID_PROGRAM, "Hash verification failed");
        }

        // Validate entry point
        if (entryPoint < 0 || entryPoint >= codeLength) {
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "Invalid entry point: 0x" + Integer.toHexString(entryPoint) + " (code size: " + codeLength + ")");
        }

        return ProgramImage.builder()
            .formatVersion(formatVersion)
            .isaVersion(isaVersion)
            .minAbiVersion(minAbiVersion)
            .minFirmwareVersion(minFirmwareVersion)
            .entryPoint(entryPoint)
            .code(code)
            .constants(constants)
            .capabilities(capabilities.toArray(new String[0]))
            .hash(hash)
            .build();
    }

    private static boolean verifyHash(byte[] data, int hashOffset, byte[] expectedHash) {
        try {
            // Compute SHA-256 of everything except the hash field itself
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Hash header + code + constants + capabilities (everything before hash)
            digest.update(data, 0, hashOffset);
            byte[] computed = digest.digest();
            return MessageDigest.isEqual(computed, expectedHash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 should always be available
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
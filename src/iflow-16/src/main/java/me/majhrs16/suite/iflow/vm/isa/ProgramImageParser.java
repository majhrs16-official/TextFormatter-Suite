package me.majhrs16.suite.iflow.vm.isa;

import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the .flow binary format (format version 2).
 * <p>
 * The file starts with a 4-byte magic number ("FLOW") so a foreign or truncated
 * file is rejected as "not a .flow" instead of failing later with a confusing
 * version or hash error (IFLOW-AUD-038). Format 2 also carries an optional,
 * length-prefixed debug section (IFLOW-AUD-037) that older readers did not have.
 * </p>
 */
public final class ProgramImageParser {

    /** Magic number at the start of every .flow file: ASCII "FLOW". */
    public static final int MAGIC = 0x464C4F57;

    /** Fixed header size of format 2 (bytes before the code section). */
    public static final int HEADER_SIZE = 20;

    public static final int CURRENT_FORMAT_VERSION = ProgramImage.CURRENT_FORMAT_VERSION;
    public static final int CURRENT_ISA_VERSION = ProgramImage.CURRENT_ISA_VERSION;
    public static final int CURRENT_ABI_VERSION = ProgramImage.CURRENT_ABI_VERSION;
    public static final int CURRENT_FIRMWARE_VERSION = ProgramImage.CURRENT_FIRMWARE_VERSION;

    public static ProgramImage parse(byte[] data) {
        if (data.length < HEADER_SIZE) {
            throw new VmException(VmException.Code.INVALID_PROGRAM, "Data too short for header");
        }

        int offset = 0;

        // Magic number: reject anything that is not a .flow file outright.
        int magic = ((data[offset++] & 0xFF) << 24)
            | ((data[offset++] & 0xFF) << 16)
            | ((data[offset++] & 0xFF) << 8)
            | (data[offset++] & 0xFF);
        if (magic != MAGIC) {
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "Not a .flow file (bad magic: 0x" + Integer.toHexString(magic)
                    + ", expected 0x" + Integer.toHexString(MAGIC) + ")");
        }

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
        if (minAbiVersion != CURRENT_ABI_VERSION) {
            // Deliberate exact-match (IFLOW-AUD-043): the ABI is the calling
            // convention of the HOST services (register roles, status codes, buffer
            // semantics). Accepting an older/newer ABI would risk decoding a call
            // that looks right but is not, i.e. an unexpected failure at run time.
            // Requiring total equality guarantees the program was built for exactly
            // this runtime; the loader's documented contract is all-or-nothing.
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "Incompatible ABI version: program requires ABI " + minAbiVersion
                    + ", runtime implements " + CURRENT_ABI_VERSION);
        }
        if (minFirmwareVersion > CURRENT_FIRMWARE_VERSION) {
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "Minimum firmware version too new: " + minFirmwareVersion + " (max " + CURRENT_FIRMWARE_VERSION + ")");
        }

        int entryPoint = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        int codeLength = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        int constantsLength = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        int capabilitiesLength = data[offset++] & 0xFF;
        offset++; // reserved
        int debugLength = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        offset += 2; // reserved

        // Validate code length
        if (codeLength < 0 || codeLength > 0xFFFF) {
            throw new VmException(VmException.Code.INVALID_PROGRAM, "Invalid code length: " + codeLength);
        }
        // Code may span the whole code region (firmware ROM at 0x0000 + user code
        // at 0x1000). The bound comes from the single-source memory map so it can
        // never drift from the engine's mapping.
        if (codeLength > VmMemoryMap.CODE_MAX_SIZE) {
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "Code section too large: " + codeLength + " bytes, maximum is "
                    + VmMemoryMap.CODE_MAX_SIZE + " bytes (" + VmMemoryMap.CODE_PAGES + " pages)");
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
        // The constants section maps into a single read-only page; keep it there
        // so it can never spill into the data region (see VmMemoryMap).
        if (constantsLength * 2 > VmMemoryMap.CONSTANTS_MAX_SIZE) {
            throw new VmException(VmException.Code.INVALID_PROGRAM,
                "Constants section too large: " + (constantsLength * 2) + " bytes, maximum is "
                    + VmMemoryMap.CONSTANTS_MAX_SIZE + " bytes (" + VmMemoryMap.CONSTANTS_PAGES + " page)");
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

        // Read the optional debug section (format 2). It is part of the hashed
        // payload (it sits before the hash field) and is purely informational:
        // the runtime never executes it. A reader that does not understand it can
        // still use the file, but the size is declared so nothing is misread.
        String debugInfo = "";
        if (debugLength > 0) {
            if (offset + debugLength > data.length) {
                throw new VmException(VmException.Code.INVALID_PROGRAM, "Debug section truncated");
            }
            debugInfo = new String(data, offset, debugLength, StandardCharsets.UTF_8);
            offset += debugLength;
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
            .debugInfo(debugInfo)
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
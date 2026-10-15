package me.majhrs16.suite.iflow.vm.isa;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for .flow binary format.
 */
public final class ProgramImageParser {

    public static ProgramImage parse(byte[] data) {
        if (data.length < 16) {
            throw new IllegalArgumentException("Data too short for header");
        }

        int offset = 0;
        int formatVersion = data[offset++] & 0xFF;
        int isaVersion = data[offset++] & 0xFF;
        int minAbiVersion = data[offset++] & 0xFF;
        int minFirmwareVersion = data[offset++] & 0xFF;

        int entryPoint = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        int codeLength = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        int constantsLength = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        int capabilitiesLength = data[offset++] & 0xFF;
        offset += 5; // padding

        // Read code
        if (offset + codeLength > data.length) {
            throw new IllegalArgumentException("Code section truncated");
        }
        byte[] code = new byte[codeLength];
        System.arraycopy(data, offset, code, 0, codeLength);
        offset += codeLength;

        // Read constants
        if (offset + constantsLength * 2 > data.length) {
            throw new IllegalArgumentException("Constants section truncated");
        }
        int[] constants = new int[constantsLength];
        for (int i = 0; i < constantsLength; i++) {
            constants[i] = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF);
        }

        // Read capabilities
        List<String> capabilities = new ArrayList<>();
        for (int i = 0; i < capabilitiesLength; i++) {
            StringBuilder sb = new StringBuilder();
            while (offset < data.length && data[offset] != 0) {
                sb.append((char) data[offset++]);
            }
            offset++; // skip null terminator
            capabilities.add(sb.toString());
        }

        // Read hash (32 bytes)
        byte[] hash = new byte[32];
        if (offset + 32 <= data.length) {
            System.arraycopy(data, offset, hash, 0, 32);
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
}
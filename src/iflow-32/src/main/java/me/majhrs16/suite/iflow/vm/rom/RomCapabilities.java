package me.majhrs16.suite.iflow.vm.rom;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strict decoder for the capability blob stored in {@code PRG3}.
 *
 * <p>The blob is {@code u16 count} followed by {@code u16 byteLength + UTF-8}
 * entries. Every declared capability must be one of the baseline names, the
 * two sync bridge names, or a concrete {@code sync.<backend>.<action>} /
 * {@code module.<moduleId>.<action>} name. An unknown or malformed capability
 * invalidates the ROM.</p>
 */
final class RomCapabilities {

    static final int MAX_ENTRIES = 256;
    static final int MAX_BYTES = 128;

    private static final Set<String> BASELINE = Set.of(
            "iflow.property.read",
            "iflow.property.write",
            "iflow.permission.check",
            "iflow.route.decision",
            "iflow.transform.apply",
            "iflow.message.id"
    );

    private RomCapabilities() {
    }

    static List<String> parse(byte[] data, int offset, int length) {
        if (length == 0) {
            return List.of();
        }
        if (length < 2 || offset < 0 || offset + length > data.length) {
            throw new InvalidRomException("capability blob is truncated");
        }

        int count = u16(data, offset);
        if (count > MAX_ENTRIES) {
            throw new InvalidRomException("capability count exceeds " + MAX_ENTRIES);
        }

        int cursor = offset + 2;
        int end = offset + length;
        List<String> capabilities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (cursor + 2 > end) {
                throw new InvalidRomException("capability entry is truncated");
            }
            int entryLength = u16(data, cursor);
            cursor += 2;
            if (entryLength > MAX_BYTES) {
                throw new InvalidRomException("capability exceeds " + MAX_BYTES + " bytes");
            }
            if (cursor + entryLength > end) {
                throw new InvalidRomException("capability entry exceeds its blob");
            }
            String value = decodeUtf8(data, cursor, entryLength);
            if (value.isEmpty()) {
                throw new InvalidRomException("capability name is empty");
            }
            if (value.indexOf('\0') >= 0) {
                throw new InvalidRomException("capability name contains NUL");
            }
            if (!isKnown(value)) {
                throw new InvalidRomException("unknown capability: " + value);
            }
            capabilities.add(value);
            cursor += entryLength;
        }
        if (cursor != end) {
            throw new InvalidRomException("capability blob has trailing bytes");
        }
        return List.copyOf(capabilities);
    }

    static boolean isKnown(String value) {
        if (BASELINE.contains(value) || "sync.surface".equals(value) || "sync.invoke".equals(value)) {
            return true;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length < 3) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
        }
        return "sync".equals(parts[0]) || "module".equals(parts[0]);
    }

    private static String decodeUtf8(byte[] data, int offset, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidRomException("capability is not valid UTF-8");
        }
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}

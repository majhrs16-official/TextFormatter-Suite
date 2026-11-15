package me.majhrs16.suite.iflow.vm.rom;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds valid ROM fixtures and provides the byte-level mutators used by the
 * parser tests. The builder intentionally allows invalid combinations; the
 * parser is the component under test.
 */
final class RomFixture {

    static final int PAGE = 4096;
    static final int USER_BASE = 0x0200_0000;
    static final int KERNEL_BASE = 0x8000_0000;
    static final int KERNEL_LOAD = 0x8000_1000;
    static final int R = 1;
    static final int W = 2;
    static final int X = 4;
    static final int U = 8;

    private RomFixture() {
    }

    static byte[] minimal() {
        return standard().build();
    }

    static Builder standard() {
        return new Builder()
                .bios(new byte[]{0, 0, 0, 0}, PAGE)
                .boot(new byte[]{0, 0, 0, 0}, KERNEL_BASE, PAGE)
                .kernel(new byte[]{0, 0, 0, 0}, KERNEL_LOAD, PAGE)
                .program(new byte[]{0, 0, 0, 0}, USER_BASE, PAGE);
    }

    static byte[] programData(byte[] code, byte[] data, List<String> capabilities) {
        byte[] blob = capabilities(capabilities);
        int codeOffset = 32;
        int codeEnd = codeOffset + code.length;
        int dataOffset = data.length == 0 ? 0 : codeEnd;
        int dataEnd = dataOffset + data.length;
        int capabilitiesOffset = blob.length == 0 ? 0 : (data.length == 0 ? codeEnd : dataEnd);
        int total = 32 + code.length + data.length + blob.length;

        byte[] result = new byte[total];
        result[0] = 'P';
        result[1] = 'R';
        result[2] = 'G';
        result[3] = '3';
        putU32(result, 4, codeOffset);
        putU32(result, 8, codeEnd);
        putU32(result, 12, dataOffset);
        putU32(result, 16, dataEnd);
        putU32(result, 20, capabilitiesOffset);
        putU32(result, 24, blob.length);
        putU32(result, 28, 0);
        System.arraycopy(code, 0, result, codeOffset, code.length);
        if (data.length > 0) {
            System.arraycopy(data, 0, result, dataOffset, data.length);
        }
        if (blob.length > 0) {
            System.arraycopy(blob, 0, result, capabilitiesOffset, blob.length);
        }
        return result;
    }

    static byte[] capabilities(List<String> capabilities) {
        if (capabilities.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, capabilities.size());
        for (String capability : capabilities) {
            byte[] bytes = capability.getBytes(StandardCharsets.UTF_8);
            writeU16(out, bytes.length);
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    static int tocIndex(byte[] rom, RomSegmentType type) {
        int toc = (int) u32(rom, 28);
        int count = u16(rom, 32);
        for (int i = 0; i < count; i++) {
            if ((rom[toc + i * 64] & 0xFF) == type.code()) {
                return i;
            }
        }
        throw new IllegalArgumentException("type not present: " + type);
    }

    static int tocEntryOffset(byte[] rom, int index) {
        return (int) u32(rom, 28) + index * 64;
    }

    static int segmentOffset(byte[] rom, int index) {
        return (int) u32(rom, tocEntryOffset(rom, index) + 4);
    }

    static int segmentSize(byte[] rom, int index) {
        return (int) u32(rom, tocEntryOffset(rom, index) + 8);
    }

    static int programOffset(byte[] rom) {
        return segmentOffset(rom, tocIndex(rom, RomSegmentType.PROGRAM));
    }

    static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    static void putU32(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    static long u32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24);
    }

    static void fixGlobalHash(byte[] rom) {
        Arrays.fill(rom, 48, 80, (byte) 0);
        byte[] hash = sha256(rom, 0, rom.length);
        System.arraycopy(hash, 0, rom, 48, 32);
    }

    static void fixSegmentHash(byte[] rom, int index) {
        int entry = tocEntryOffset(rom, index);
        int offset = (int) u32(rom, entry + 4);
        int size = (int) u32(rom, entry + 8);
        byte[] hash = sha256(rom, offset, size);
        System.arraycopy(hash, 0, rom, entry + 24, 32);
    }

    static void fixAllHashes(byte[] rom) {
        int count = u16(rom, 32);
        for (int i = 0; i < count; i++) {
            fixSegmentHash(rom, i);
        }
        fixGlobalHash(rom);
    }

    static byte[] sha256(byte[] data, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data, offset, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    static final class Builder {

        private final List<Spec> segments = new ArrayList<>();
        private boolean signed;
        private byte[] signature = new byte[0];
        private int resetVector;

        Builder bios(byte[] data, int memSize) {
            return segment(RomSegmentType.BIOS.code(), R | X, 0, 0, memSize, data);
        }

        Builder boot(byte[] data, int loadAddress, int memSize) {
            return segment(RomSegmentType.BOOT.code(), R | X, loadAddress, loadAddress, memSize, data);
        }

        Builder kernel(byte[] data, int loadAddress, int memSize) {
            return segment(RomSegmentType.KERNEL.code(), R | X, loadAddress, loadAddress, memSize, data);
        }

        Builder program(byte[] code, int loadAddress, int memSize) {
            return program(code, new byte[0], List.of(), loadAddress, memSize);
        }

        Builder program(byte[] code, byte[] data, List<String> capabilities,
                        int loadAddress, int memSize) {
            byte[] segment = programData(code, data, capabilities);
            return segment(RomSegmentType.PROGRAM.code(), R | X | U,
                    loadAddress, loadAddress + 32, memSize, segment);
        }

        Builder segment(int type, int flags, int loadAddress, int entryPoint,
                        int memSize, byte[] data) {
            segments.add(new Spec(type, flags, loadAddress, entryPoint, memSize, data.clone()));
            return this;
        }

        Builder remove(RomSegmentType type) {
            segments.removeIf(spec -> spec.type == type.code());
            return this;
        }

        Builder signed(byte[] signature) {
            this.signed = true;
            this.signature = signature.clone();
            return this;
        }

        Builder signedFlagOnly() {
            this.signed = true;
            this.signature = new byte[0];
            return this;
        }

        Builder unsignedWithSignature(byte[] signature) {
            this.signed = false;
            this.signature = signature.clone();
            return this;
        }

        Builder resetVector(int resetVector) {
            this.resetVector = resetVector;
            return this;
        }

        byte[] build() {
            int tocBytes = 80 + segments.size() * 64;
            int dataBytes = 0;
            for (Spec spec : segments) {
                dataBytes += spec.data.length;
            }
            int signatureOffset = tocBytes + dataBytes;
            int total = signatureOffset + signature.length;
            byte[] rom = new byte[total];

            rom[0] = 'F';
            rom[1] = 'L';
            rom[2] = 'O';
            rom[3] = 'W';
            rom[4] = 'R';
            rom[5] = 'O';
            rom[6] = 'M';
            rom[7] = 0;
            putU16(rom, 8, 3);
            putU16(rom, 10, 32);
            putU16(rom, 12, 3);
            putU16(rom, 14, 80);
            putU32(rom, 16, signed ? 1 : 0);
            putU32(rom, 20, total);
            putU32(rom, 24, resetVector);
            putU32(rom, 28, 80);
            putU16(rom, 32, segments.size());
            putU16(rom, 34, 64);
            putU32(rom, 36, 1);
            if (signature.length > 0) {
                putU32(rom, 40, signatureOffset);
                putU32(rom, 44, signature.length);
            }

            int cursor = tocBytes;
            for (int i = 0; i < segments.size(); i++) {
                Spec spec = segments.get(i);
                int entry = 80 + i * 64;
                rom[entry] = (byte) spec.type;
                rom[entry + 1] = (byte) spec.flags;
                putU32(rom, entry + 4, cursor);
                putU32(rom, entry + 8, spec.data.length);
                putU32(rom, entry + 12, spec.loadAddress);
                putU32(rom, entry + 16, spec.entryPoint);
                putU32(rom, entry + 20, spec.memSize);
                byte[] hash = sha256(spec.data, 0, spec.data.length);
                System.arraycopy(hash, 0, rom, entry + 24, 32);
                System.arraycopy(spec.data, 0, rom, cursor, spec.data.length);
                cursor += spec.data.length;
            }
            if (signature.length > 0) {
                System.arraycopy(signature, 0, rom, signatureOffset, signature.length);
            }
            fixGlobalHash(rom);
            return rom;
        }
    }

    private record Spec(int type, int flags, int loadAddress, int entryPoint,
                        int memSize, byte[] data) {
    }
}

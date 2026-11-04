package me.majhrs16.suite.iflow.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Constructor mínimo de módulos WebAssembly para tests.
 *
 * <p>No sustituye a un toolchain WAT: solo emite las secciones que necesitan
 * los tests de contrato (type, import, function, export y code). Los cuerpos
 * se escriben como instrucciones binarias; el builder añade el vector de
 * locales vacío y el {@code end} final.</p>
 */
final class WasmTestModule {

    static final int I32 = 0x7f;
    static final int I64 = 0x7e;

    private final List<byte[]> types = new ArrayList<>();
    private final List<byte[]> imports = new ArrayList<>();
    private final List<Integer> functionTypes = new ArrayList<>();
    private final List<byte[]> exports = new ArrayList<>();
    private final List<byte[]> bodies = new ArrayList<>();
    private int nextFunctionIndex;

    int functionType(int[] params, int[] results) {
        byte[] encoded = new byte[1 + 1 + params.length + 1 + results.length];
        int cursor = 0;
        encoded[cursor++] = 0x60;
        encoded[cursor++] = (byte) params.length;
        for (int param : params) {
            encoded[cursor++] = (byte) param;
        }
        encoded[cursor++] = (byte) results.length;
        for (int result : results) {
            encoded[cursor++] = (byte) result;
        }
        types.add(encoded);
        return types.size() - 1;
    }

    int importFunction(String module, String name, int typeIndex) {
        imports.add(concat(string(module), string(name), new byte[]{0x00}, leb(typeIndex)));
        return nextFunctionIndex++;
    }

    int function(int typeIndex) {
        functionTypes.add(typeIndex);
        return nextFunctionIndex++;
    }

    void exportFunction(String name, int functionIndex) {
        exports.add(concat(string(name), new byte[]{0x00}, leb(functionIndex)));
    }

    /**
     * Añade el cuerpo de una función local. {@code instructions} no incluye el
     * vector de locales ni el {@code end} final.
     */
    void code(byte[] instructions) {
        bodies.add(concat(new byte[]{0x00}, instructions, new byte[]{0x0b}));
    }

    byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
        if (!types.isEmpty()) {
            out.writeBytes(section(1, vector(types)));
        }
        if (!imports.isEmpty()) {
            out.writeBytes(section(2, vector(imports)));
        }
        if (!functionTypes.isEmpty()) {
            List<byte[]> encodedTypes = new ArrayList<>();
            for (int typeIndex : functionTypes) {
                encodedTypes.add(leb(typeIndex));
            }
            out.writeBytes(section(3, vector(encodedTypes)));
        }
        if (!exports.isEmpty()) {
            out.writeBytes(section(7, vector(exports)));
        }
        if (!bodies.isEmpty()) {
            List<byte[]> encodedBodies = new ArrayList<>();
            for (byte[] body : bodies) {
                encodedBodies.add(concat(leb(body.length), body));
            }
            out.writeBytes(section(10, vector(encodedBodies)));
        }
        return out.toByteArray();
    }

    private static byte[] section(int id, byte[] payload) {
        return concat(new byte[]{(byte) id}, leb(payload.length), payload);
    }

    private static byte[] vector(List<byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(leb(entries.size()));
        for (byte[] entry : entries) {
            out.writeBytes(entry);
        }
        return out.toByteArray();
    }

    private static byte[] string(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return concat(leb(bytes.length), bytes);
    }

    private static byte[] leb(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int remaining = value;
        do {
            int next = remaining & 0x7f;
            remaining >>>= 7;
            if (remaining != 0) {
                next |= 0x80;
            }
            out.write(next);
        } while (remaining != 0);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            out.writeBytes(array);
        }
        return out.toByteArray();
    }
}

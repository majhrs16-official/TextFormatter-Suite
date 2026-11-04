package me.majhrs16.suite.iflow.wasm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Codec TLV del contrato de política v3.
 *
 * <p>Un valor se codifica como {@code [type:u8][length:u32 LE][payload]}. El
 * tipo {@link #TYPE_NULL} representa ausencia explícita y se distingue de una
 * cadena vacía ({@link #TYPE_STRING} con longitud cero). Las cadenas se
 * codifican en UTF-8 estricto: no se truncan caracteres y se rechazan
 * secuencias inválidas o surrogates sueltos.</p>
 *
 * <p>El codec es deliberadamente pequeño: cubre un valor por operación. Los
 * mapas de contexto se construirán concatenando valores TLV con un tag de
 * campo cuando W2 implemente las host functions.</p>
 */
public final class TlvCodec {

    public static final int TYPE_NULL = 0;
    public static final int TYPE_STRING = 1;
    public static final int TYPE_I64 = 2;
    public static final int TYPE_BOOL = 3;
    public static final int TYPE_BYTES = 4;

    /** Bytes de cabecera: 1 de tipo + 4 de longitud. */
    public static final int HEADER_BYTES = 5;

    private TlvCodec() {
    }

    public static byte[] encodeNull() {
        return encode(TYPE_NULL, new byte[0]);
    }

    public static byte[] encodeString(String value) {
        Objects.requireNonNull(value, "value");
        return encode(TYPE_STRING, encodeUtf8(value));
    }

    public static byte[] encodeI64(long value) {
        return encode(TYPE_I64, ByteBuffer.allocate(Long.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value)
            .array());
    }

    public static byte[] encodeBool(boolean value) {
        return encode(TYPE_BOOL, new byte[]{(byte) (value ? 1 : 0)});
    }

    public static byte[] encodeBytes(byte[] value) {
        Objects.requireNonNull(value, "value");
        return encode(TYPE_BYTES, value);
    }

    public static TlvValue nullValue() {
        return new TlvValue(TYPE_NULL, new byte[0]);
    }

    public static TlvValue stringValue(String value) {
        Objects.requireNonNull(value, "value");
        return new TlvValue(TYPE_STRING, encodeUtf8(value));
    }

    public static TlvValue i64Value(long value) {
        return new TlvValue(TYPE_I64, ByteBuffer.allocate(Long.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value)
            .array());
    }

    public static TlvValue boolValue(boolean value) {
        return new TlvValue(TYPE_BOOL, new byte[]{(byte) (value ? 1 : 0)});
    }

    public static TlvValue bytesValue(byte[] value) {
        Objects.requireNonNull(value, "value");
        return new TlvValue(TYPE_BYTES, value);
    }

    /**
     * Decodifica UTF-8 estricto. Se usa para buffers de host que no llevan
     * cabecera TLV (nombres de canal, permisos, destinos).
     */
    public static String decodeUtf8(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("payload no es UTF-8 válido", e);
        }
    }

    public static byte[] encode(int type, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        validatePayload(type, payload);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) type);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    public static TlvValue decode(byte[] data) {
        Objects.requireNonNull(data, "data");
        return decode(data, 0, data.length);
    }

    public static TlvValue decode(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException("rango TLV inválido");
        }
        if (length < HEADER_BYTES) {
            throw new IllegalArgumentException("TLV truncado: faltan bytes de cabecera");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN);
        int type = Byte.toUnsignedInt(buffer.get());
        long declaredLength = Integer.toUnsignedLong(buffer.getInt());
        if (declaredLength > length - HEADER_BYTES) {
            throw new IllegalArgumentException("TLV truncado: longitud declarada " + declaredLength
                + " supera los bytes disponibles " + (length - HEADER_BYTES));
        }
        if (declaredLength != length - HEADER_BYTES) {
            throw new IllegalArgumentException("TLV con bytes sobrantes: declarados " + declaredLength
                + ", disponibles " + (length - HEADER_BYTES));
        }

        byte[] payload = new byte[(int) declaredLength];
        buffer.get(payload);
        return new TlvValue(type, payload);
    }

    private static byte[] encodeUtf8(String value) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value));
            byte[] out = new byte[encoded.remaining()];
            encoded.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("cadena no codificable como UTF-8", e);
        }
    }

    private static void validatePayload(int type, byte[] payload) {
        switch (type) {
            case TYPE_NULL -> {
                if (payload.length != 0) {
                    throw new IllegalArgumentException("TYPE_NULL no admite payload");
                }
            }
            case TYPE_STRING, TYPE_BYTES -> {
                // Sin restricción de longitud; el llamador controla el buffer.
            }
            case TYPE_I64 -> {
                if (payload.length != Long.BYTES) {
                    throw new IllegalArgumentException("TYPE_I64 requiere 8 bytes");
                }
            }
            case TYPE_BOOL -> {
                if (payload.length != 1 || (payload[0] != 0 && payload[0] != 1)) {
                    throw new IllegalArgumentException("TYPE_BOOL requiere un byte 0/1");
                }
            }
            default -> throw new IllegalArgumentException("tipo TLV desconocido: " + type);
        }
    }

    /**
     * Valor TLV inmutable.
     *
     * @param type    uno de {@code TYPE_*}
     * @param payload bytes del valor; se copian defensivamente
     */
    public record TlvValue(int type, byte[] payload) {

        public TlvValue {
            Objects.requireNonNull(payload, "payload");
            validatePayload(type, payload);
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        public boolean isNull() {
            return type == TYPE_NULL;
        }

        public String asString() {
            requireType(TYPE_STRING);
            return decodeUtf8(payload);
        }

        public long asI64() {
            requireType(TYPE_I64);
            return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getLong();
        }

        public boolean asBool() {
            requireType(TYPE_BOOL);
            return payload[0] == 1;
        }

        public byte[] asBytes() {
            requireType(TYPE_BYTES);
            return payload.clone();
        }

        private void requireType(int expected) {
            if (type != expected) {
                throw new IllegalStateException("se esperaba tipo TLV " + expected + " y llegó " + type);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TlvValue value)) {
                return false;
            }
            return type == value.type && Arrays.equals(payload, value.payload);
        }

        @Override
        public int hashCode() {
            return 31 * type + Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "TlvValue[type=" + type + ", length=" + payload.length + "]";
        }
    }
}

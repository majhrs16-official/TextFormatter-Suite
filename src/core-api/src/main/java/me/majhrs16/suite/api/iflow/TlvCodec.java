package me.majhrs16.suite.api.iflow;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Codec TLV neutral compartido por los motores iFlow y el puente sync.
 *
 * <p>Un valor se codifica como {@code [type:u8][length:u32 LE][payload]}. El
 * tipo {@link #TYPE_NULL} representa ausencia explícita y se distingue de una
 * cadena vacía ({@link #TYPE_STRING} con longitud cero). Las cadenas se
 * codifican en UTF-8 estricto: no se truncan caracteres y se rechazan
 * secuencias inválidas o surrogates sueltos.</p>
 *
 * <p>El codec vive en {@code core-api} porque lo comparten la VM, Wasm y el
 * puente de acciones sync (ADR-036 §16.7). Los tipos {@link #TYPE_LIST} y
 * {@link #TYPE_MAP} permiten transportar los mapas de argumentos y las
 * superficies de acciones sin inventar un formato por motor: una lista es una
 * concatenación de valores TLV y un mapa es una concatenación de pares
 * {@code [clave string][valor]}.</p>
 *
 * <p>{@link #decodePrefix(byte[])} decodifica el primer valor e ignora el
 * relleno posterior. La VM lo necesita porque su ABI pasa un buffer con
 * capacidad, no con la longitud exacta del valor.</p>
 */
public final class TlvCodec {

    public static final int TYPE_NULL = 0;
    public static final int TYPE_STRING = 1;
    public static final int TYPE_I64 = 2;
    public static final int TYPE_BOOL = 3;
    public static final int TYPE_BYTES = 4;
    public static final int TYPE_LIST = 5;
    public static final int TYPE_MAP = 6;

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

    /** Codifica una lista como concatenación de valores TLV. */
    public static byte[] encodeList(List<TlvValue> values) {
        Objects.requireNonNull(values, "values");
        return encode(TYPE_LIST, concatenate(values));
    }

    /**
     * Codifica un mapa como concatenación de pares {@code [clave][valor]}. Las
     * claves deben ser cadenas no nulas y no repetirse.
     */
    public static byte[] encodeMap(Map<String, TlvValue> values) {
        Objects.requireNonNull(values, "values");
        List<TlvValue> parts = new ArrayList<>(values.size() * 2);
        for (Map.Entry<String, TlvValue> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("clave de mapa TLV vacía");
            }
            parts.add(stringValue(key));
            parts.add(Objects.requireNonNull(entry.getValue(), "valor de " + key));
        }
        return encode(TYPE_MAP, concatenate(parts));
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

    public static TlvValue listValue(List<TlvValue> values) {
        Objects.requireNonNull(values, "values");
        return new TlvValue(TYPE_LIST, concatenate(values));
    }

    public static TlvValue mapValue(Map<String, TlvValue> values) {
        Objects.requireNonNull(values, "values");
        List<TlvValue> parts = new ArrayList<>(values.size() * 2);
        for (Map.Entry<String, TlvValue> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("clave de mapa TLV vacía");
            }
            parts.add(stringValue(key));
            parts.add(Objects.requireNonNull(entry.getValue(), "valor de " + key));
        }
        return new TlvValue(TYPE_MAP, concatenate(parts));
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

    /**
     * Decodifica el primer valor TLV del prefijo e ignora cualquier byte
     * posterior. Es el modo de lectura de la VM, cuyo buffer tiene capacidad
     * fija y no una longitud exacta.
     */
    public static TlvValue decodePrefix(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length < HEADER_BYTES) {
            throw new IllegalArgumentException("TLV truncado: faltan bytes de cabecera");
        }
        ByteBuffer header = ByteBuffer.wrap(data, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.get();
        long payloadLength = Integer.toUnsignedLong(header.getInt());
        long total = HEADER_BYTES + payloadLength;
        if (total > data.length) {
            throw new IllegalArgumentException("TLV truncado: se esperaban " + total
                + " bytes y hay " + data.length);
        }
        return decode(data, 0, (int) total);
    }

    /** Decodifica una concatenación de valores TLV. */
    public static List<TlvValue> decodeSequence(byte[] data) {
        Objects.requireNonNull(data, "data");
        List<TlvValue> values = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            if (data.length - offset < HEADER_BYTES) {
                throw new IllegalArgumentException("secuencia TLV truncada en " + offset);
            }
            ByteBuffer header = ByteBuffer.wrap(data, offset, HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
            header.get();
            long payloadLength = Integer.toUnsignedLong(header.getInt());
            long total = HEADER_BYTES + payloadLength;
            if (total > data.length - offset) {
                throw new IllegalArgumentException("secuencia TLV truncada en " + offset
                    + ": se esperaban " + total + " bytes");
            }
            values.add(decode(data, offset, (int) total));
            offset += (int) total;
        }
        return values;
    }

    private static byte[] concatenate(List<TlvValue> values) {
        int size = 0;
        for (TlvValue value : values) {
            size += HEADER_BYTES + value.payload().length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        for (TlvValue value : values) {
            buffer.put((byte) value.type());
            buffer.putInt(value.payload().length);
            buffer.put(value.payload());
        }
        return buffer.array();
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
            case TYPE_LIST -> decodeSequence(payload);
            case TYPE_MAP -> validateMap(payload);
            default -> throw new IllegalArgumentException("tipo TLV desconocido: " + type);
        }
    }

    private static void validateMap(byte[] payload) {
        List<TlvValue> parts = decodeSequence(payload);
        if (parts.size() % 2 != 0) {
            throw new IllegalArgumentException("TYPE_MAP requiere pares clave/valor");
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < parts.size(); i += 2) {
            TlvValue key = parts.get(i);
            if (key.type() != TYPE_STRING) {
                throw new IllegalArgumentException("TYPE_MAP requiere claves string");
            }
            if (!keys.add(key.asString())) {
                throw new IllegalArgumentException("TYPE_MAP con clave duplicada: " + key.asString());
            }
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

        /** @return los valores de una lista, en orden */
        public List<TlvValue> asList() {
            requireType(TYPE_LIST);
            return decodeSequence(payload);
        }

        /** @return el mapa preservando el orden de inserción */
        public Map<String, TlvValue> asMap() {
            requireType(TYPE_MAP);
            List<TlvValue> parts = decodeSequence(payload);
            Map<String, TlvValue> values = new LinkedHashMap<>();
            for (int i = 0; i < parts.size(); i += 2) {
                values.put(parts.get(i).asString(), parts.get(i + 1));
            }
            return values;
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

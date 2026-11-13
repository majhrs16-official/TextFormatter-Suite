package me.majhrs16.suite.api.iflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TlvCodecTest {

    @Test
    void roundTripsStringWithoutTruncatingUtf8() {
        String text = "ñ€😀";

        TlvCodec.TlvValue value = TlvCodec.decode(TlvCodec.encodeString(text));

        assertEquals(TlvCodec.TYPE_STRING, value.type());
        assertEquals(text, value.asString());
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, value.payload().length);
    }

    @Test
    void nullAndEmptyStringAreDistinct() {
        TlvCodec.TlvValue nullValue = TlvCodec.decode(TlvCodec.encodeNull());
        TlvCodec.TlvValue emptyValue = TlvCodec.decode(TlvCodec.encodeString(""));

        assertTrue(nullValue.isNull());
        assertFalse(emptyValue.isNull());
        assertEquals(TlvCodec.TYPE_NULL, nullValue.type());
        assertEquals(TlvCodec.TYPE_STRING, emptyValue.type());
        assertNotEquals(nullValue, emptyValue);
    }

    @Test
    void roundTripsI64() {
        for (long value : new long[]{Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
            TlvCodec.TlvValue decoded = TlvCodec.decode(TlvCodec.encodeI64(value));
            assertEquals(TlvCodec.TYPE_I64, decoded.type());
            assertEquals(value, decoded.asI64());
        }
    }

    @Test
    void roundTripsBoolean() {
        assertTrue(TlvCodec.decode(TlvCodec.encodeBool(true)).asBool());
        assertFalse(TlvCodec.decode(TlvCodec.encodeBool(false)).asBool());
    }

    @Test
    void roundTripsBytes() {
        byte[] payload = {0, 1, (byte) 0xFF, 42};

        TlvCodec.TlvValue decoded = TlvCodec.decode(TlvCodec.encodeBytes(payload));

        assertEquals(TlvCodec.TYPE_BYTES, decoded.type());
        assertArrayEquals(payload, decoded.asBytes());
    }

    @Test
    void rejectsTruncatedHeader() {
        assertThrows(IllegalArgumentException.class,
            () -> TlvCodec.decode(new byte[]{1, 0, 0}));
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] data = {TlvCodec.TYPE_STRING, 4, 0, 0, 0, 'a', 'b'};
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.decode(data));
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] data = {TlvCodec.TYPE_NULL, 0, 0, 0, 0, 1};
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.decode(data));
    }

    @Test
    void rejectsUnknownType() {
        byte[] data = {9, 0, 0, 0, 0};
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.decode(data));
    }

    @Test
    void rejectsWrongI64Length() {
        byte[] data = {TlvCodec.TYPE_I64, 1, 0, 0, 0, 0};
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.decode(data));
    }

    @Test
    void rejectsInvalidBooleanPayload() {
        byte[] data = {TlvCodec.TYPE_BOOL, 1, 0, 0, 0, 2};
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.decode(data));
    }

    @Test
    void rejectsInvalidUtf8() {
        byte[] data = {TlvCodec.TYPE_STRING, 1, 0, 0, 0, (byte) 0xC3};
        TlvCodec.TlvValue value = TlvCodec.decode(data);
        assertThrows(IllegalArgumentException.class, value::asString);
    }

    @Test
    void rejectsUnpairedSurrogateWhenEncoding() {
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.encodeString("\uD800"));
    }

    @Test
    void roundTripsListPreservingOrder() {
        TlvCodec.TlvValue value = TlvCodec.decode(TlvCodec.encodeList(List.of(
            TlvCodec.stringValue("a"),
            TlvCodec.i64Value(7),
            TlvCodec.boolValue(true))));

        assertEquals(TlvCodec.TYPE_LIST, value.type());
        List<TlvCodec.TlvValue> items = value.asList();
        assertEquals(3, items.size());
        assertEquals("a", items.get(0).asString());
        assertEquals(7, items.get(1).asI64());
        assertTrue(items.get(2).asBool());
    }

    @Test
    void roundTripsMapPreservingOrder() {
        Map<String, TlvCodec.TlvValue> source = new LinkedHashMap<>();
        source.put("backend", TlvCodec.stringValue("discord"));
        source.put("count", TlvCodec.i64Value(2));

        TlvCodec.TlvValue value = TlvCodec.decode(TlvCodec.encodeMap(source));

        assertEquals(TlvCodec.TYPE_MAP, value.type());
        Map<String, TlvCodec.TlvValue> decoded = value.asMap();
        assertEquals(List.of("backend", "count"), List.copyOf(decoded.keySet()));
        assertEquals("discord", decoded.get("backend").asString());
        assertEquals(2, decoded.get("count").asI64());
    }

    @Test
    void rejectsMapWithOddPartsOrDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.encode(TlvCodec.TYPE_MAP,
            TlvCodec.encodeString("only-key")));

        assertThrows(IllegalArgumentException.class, () -> TlvCodec.encode(TlvCodec.TYPE_MAP, concat(
            TlvCodec.encodeString("k"), TlvCodec.encodeNull(),
            TlvCodec.encodeString("k"), TlvCodec.encodeNull())));
    }

    @Test
    void rejectsMapWithNonStringKey() {
        byte[] payload = concat(TlvCodec.encodeI64(1), TlvCodec.encodeNull());
        assertThrows(IllegalArgumentException.class,
            () -> TlvCodec.encode(TlvCodec.TYPE_MAP, payload));
    }

    @Test
    void decodePrefixIgnoresTrailingCapacity() {
        byte[] encoded = TlvCodec.encodeString("hello");
        byte[] padded = new byte[encoded.length + 16];
        System.arraycopy(encoded, 0, padded, 0, encoded.length);

        assertEquals("hello", TlvCodec.decodePrefix(padded).asString());
    }

    @Test
    void decodeSequenceReadsEveryValue() {
        byte[] data = concat(
            TlvCodec.encodeString("a"),
            TlvCodec.encodeI64(1),
            TlvCodec.encodeNull());

        List<TlvCodec.TlvValue> values = TlvCodec.decodeSequence(data);

        assertEquals(3, values.size());
        assertEquals("a", values.get(0).asString());
        assertEquals(1, values.get(1).asI64());
        assertTrue(values.get(2).isNull());
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] out = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    @Test
    void copiesPayloadDefensively() {
        byte[] payload = {1, 2, 3};
        TlvCodec.TlvValue value = TlvCodec.decode(TlvCodec.encodeBytes(payload));

        payload[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, value.asBytes());

        byte[] returned = value.asBytes();
        returned[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, value.asBytes());
    }
}

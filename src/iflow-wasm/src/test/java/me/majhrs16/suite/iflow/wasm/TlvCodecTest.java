package me.majhrs16.suite.iflow.wasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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

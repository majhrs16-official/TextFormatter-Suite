package me.majhrs16.suite.api.sync;

import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.iflow.TlvCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncBridgeCodecTest {

    @Test
    void roundTripsSurfaceRequest() {
        byte[] request = SyncBridgeCodec.encodeSurfaceRequest("discord");

        assertEquals("discord", SyncBridgeCodec.decodeSurfaceRequest(request));
        assertEquals("", SyncBridgeCodec.decodeSurfaceRequest(
                SyncBridgeCodec.encodeSurfaceRequest(null)));
    }

    @Test
    void roundTripsInvokeRequestWithEveryNeutralValue() {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("text", "hello");
        args.put("count", 3);
        args.put("flag", true);
        args.put("raw", new byte[]{1, 2, 3});
        args.put("kind", ActionKind.WRITE);
        args.put("list", List.of("a", 1));
        args.put("nested", Map.of("k", "v"));

        SyncBridgeCodec.InvokeRequest decoded = SyncBridgeCodec.decodeInvokeRequest(
                SyncBridgeCodec.encodeInvokeRequest(
                        new SyncBridgeCodec.InvokeRequest("discord", "channel.create", args)));

        assertEquals("discord", decoded.backend());
        assertEquals("channel.create", decoded.action());
        assertEquals("hello", decoded.args().get("text"));
        assertEquals(3L, decoded.args().get("count"));
        assertEquals(true, decoded.args().get("flag"));
        assertEquals(List.of(1, 2, 3), bytes(decoded.args().get("raw")));
        assertEquals("WRITE", decoded.args().get("kind"));
        assertEquals(List.of("a", 1L), decoded.args().get("list"));
        assertEquals(Map.of("k", "v"), decoded.args().get("nested"));
    }

    @Test
    void roundTripsEverySyncResult() {
        List<SyncResult> results = List.of(
                new SyncResult.Ok("value"),
                new SyncResult.Ok(null),
                new SyncResult.Denied("no"),
                new SyncResult.Unsupported("missing"),
                new SyncResult.InvalidArgument("bad"),
                new SyncResult.NotFound("absent"),
                new SyncResult.Conflict("twice"),
                new SyncResult.RateLimited(1500),
                new SyncResult.Timeout("slow"),
                new SyncResult.HostFailure("boom"));

        for (SyncResult result : results) {
            assertEquals(result, SyncBridgeCodec.decodeResult(encode(result)), result.toString());
        }
    }

    @Test
    void roundTripsSurfaceResponse() {
        SyncSurface discord = new SyncSurface("discord", SemVer.of(1, 0, 0), Set.of(
                new SyncActionSpec("backend.info", ActionKind.READ, List.of(), null, true, 5000),
                new SyncActionSpec("channel.create", ActionKind.WRITE,
                        List.of(
                                SyncParamSpec.required("guild", SyncValueType.CHANNEL_REF),
                                SyncParamSpec.optional("topic", SyncValueType.STRING)),
                        SyncParamSpec.optional("id", SyncValueType.STRING),
                        false, 10_000)));
        SyncSurface telegram = SyncSurface.lcm("telegram", SemVer.of(1, 2, 3));

        TlvCodec.TlvValue encoded = SyncBridgeCodec.encodeSurfaceResponse(
                List.of(telegram, discord));
        List<SyncSurface> decoded = SyncBridgeCodec.decodeSurfaceResponse(
                TlvCodec.encode(encoded.type(), encoded.payload()));

        assertEquals(2, decoded.size());
        assertEquals("discord", decoded.get(0).backend());
        assertEquals("telegram", decoded.get(1).backend());
        assertEquals(discord, decoded.get(0));
        assertEquals(telegram, decoded.get(1));
    }

    @Test
    void rejectsUnknownStatus() {
        Map<String, TlvCodec.TlvValue> payload = new java.util.LinkedHashMap<>();
        payload.put(SyncBridgeCodec.KEY_STATUS, TlvCodec.stringValue("MAYBE"));
        byte[] data = TlvCodec.encodeMap(payload);

        assertThrows(IllegalArgumentException.class,
                () -> SyncBridgeCodec.decodeResult(data));
    }

    @Test
    void rejectsUnsupportedNeutralValue() {
        assertThrows(IllegalArgumentException.class,
                () -> SyncBridgeCodec.encodeValue(new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> SyncBridgeCodec.encodeValue(Map.of(1, "non-string key")));
    }

    @Test
    void rejectsMissingRequestKeys() {
        byte[] data = TlvCodec.encodeMap(Map.of(
                SyncBridgeCodec.KEY_BACKEND, TlvCodec.stringValue("discord")));

        assertThrows(IllegalArgumentException.class,
                () -> SyncBridgeCodec.decodeInvokeRequest(data));
    }

    @Test
    void rejectsWrongPayloadTypes() {
        byte[] wrong = TlvCodec.encodeString("discord");

        assertThrows(IllegalArgumentException.class,
                () -> SyncBridgeCodec.decodeInvokeRequest(wrong));
        assertThrows(IllegalArgumentException.class,
                () -> SyncBridgeCodec.decodeSurfaceResponse(wrong));
    }

    @Test
    void statusNameCoversEveryResult() {
        assertEquals(SyncBridgeCodec.STATUS_OK,
                SyncBridgeCodec.statusName(new SyncResult.Ok(null)));
        assertEquals(SyncBridgeCodec.STATUS_DENIED,
                SyncBridgeCodec.statusName(new SyncResult.Denied("no")));
        assertEquals(SyncBridgeCodec.STATUS_UNSUPPORTED,
                SyncBridgeCodec.statusName(new SyncResult.Unsupported("missing")));
        assertEquals(SyncBridgeCodec.STATUS_INVALID_ARGUMENT,
                SyncBridgeCodec.statusName(new SyncResult.InvalidArgument("bad")));
        assertEquals(SyncBridgeCodec.STATUS_NOT_FOUND,
                SyncBridgeCodec.statusName(new SyncResult.NotFound("absent")));
        assertEquals(SyncBridgeCodec.STATUS_CONFLICT,
                SyncBridgeCodec.statusName(new SyncResult.Conflict("twice")));
        assertEquals(SyncBridgeCodec.STATUS_RATE_LIMITED,
                SyncBridgeCodec.statusName(new SyncResult.RateLimited(1_500)));
        assertEquals(SyncBridgeCodec.STATUS_TIMEOUT,
                SyncBridgeCodec.statusName(new SyncResult.Timeout("slow")));
        assertEquals(SyncBridgeCodec.STATUS_HOST_FAILURE,
                SyncBridgeCodec.statusName(new SyncResult.HostFailure("boom")));
    }

    @Test
    void nullAndFalseAndZeroSurviveTheRoundTrip() {
        SyncResult.Ok zero = new SyncResult.Ok(0L);
        SyncResult.Ok flag = new SyncResult.Ok(false);

        assertEquals(zero, SyncBridgeCodec.decodeResult(encode(zero)));
        assertEquals(flag, SyncBridgeCodec.decodeResult(encode(flag)));
        assertNull(((SyncResult.Ok) SyncBridgeCodec.decodeResult(
                encode(new SyncResult.Ok(null)))).value());
        assertEquals(Boolean.FALSE, ((SyncResult.Ok) SyncBridgeCodec.decodeResult(
                encode(new SyncResult.Ok(false)))).value());
    }

    private static byte[] encode(SyncResult result) {
        TlvCodec.TlvValue value = SyncBridgeCodec.encodeResult(result);
        return TlvCodec.encode(value.type(), value.payload());
    }

    private static List<Integer> bytes(Object value) {
        byte[] raw = (byte[]) value;
        List<Integer> out = new java.util.ArrayList<>(raw.length);
        for (byte b : raw) {
            out.add((int) b);
        }
        return out;
    }
}

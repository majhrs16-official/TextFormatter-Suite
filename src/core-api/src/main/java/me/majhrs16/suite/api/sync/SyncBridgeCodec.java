package me.majhrs16.suite.api.sync;

import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.iflow.TlvCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wire contract of the iFlow sync bridge (ADR-036 §16.7).
 *
 * <p>The VM and Wasm engines expose the same two generic operations:
 * {@code sync_surface} to discover the published actions and
 * {@code sync_invoke} to execute one. Both use the neutral {@link TlvCodec}
 * and the same payloads, so a guest program and its tests are independent of
 * the engine that runs it.</p>
 *
 * <h2>Payloads</h2>
 * <pre>
 * sync_surface request   TLV string: backend namespace, empty for every backend
 * sync_surface response  TLV list of surfaces
 * sync_invoke request    TLV map: backend, action, args
 * sync_invoke response   TLV map: status, result | reason
 * </pre>
 *
 * <p>A surface is a map {@code backend, contractVersion, actions}; an action is
 * a map {@code id, kind, params, result, idempotent, timeoutMillis}; a parameter
 * is a map {@code name, type, required, item}. Arguments and results use the
 * neutral value mapping of {@link #encodeValue(Object)}: null, string, i64,
 * bool, bytes, list and map.</p>
 *
 * <p>The {@code status} field is canonical and engine-independent:
 * {@code OK}, {@code DENIED}, {@code UNSUPPORTED}, {@code INVALID_ARGUMENT},
 * {@code NOT_FOUND}, {@code CONFLICT}, {@code RATE_LIMITED}, {@code TIMEOUT} or
 * {@code HOST_FAILURE}. Each engine also maps it to its own host status
 * register; the payload never depends on the engine's numbering.</p>
 */
public final class SyncBridgeCodec {

    /** Request key: backend namespace. */
    public static final String KEY_BACKEND = "backend";
    /** Request key: local action id. */
    public static final String KEY_ACTION = "action";
    /** Request key: argument map. */
    public static final String KEY_ARGS = "args";
    /** Response key: canonical status name. */
    public static final String KEY_STATUS = "status";
    /** Response key: successful value. */
    public static final String KEY_RESULT = "result";
    /** Response key: failure reason. */
    public static final String KEY_REASON = "reason";

    /** Surface key: backend namespace. */
    public static final String KEY_CONTRACT_VERSION = "contractVersion";
    /** Surface key: published actions. */
    public static final String KEY_ACTIONS = "actions";
    /** Action key: local id. */
    public static final String KEY_ID = "id";
    /** Action key: authorization nature. */
    public static final String KEY_KIND = "kind";
    /** Action key: ordered parameters. */
    public static final String KEY_PARAMS = "params";
    /** Action key: idempotency flag. */
    public static final String KEY_IDEMPOTENT = "idempotent";
    /** Action key: execution bound. */
    public static final String KEY_TIMEOUT_MILLIS = "timeoutMillis";
    /** Parameter key: name. */
    public static final String KEY_NAME = "name";
    /** Parameter key: neutral type. */
    public static final String KEY_TYPE = "type";
    /** Parameter key: required flag. */
    public static final String KEY_REQUIRED = "required";
    /** Parameter key: element spec for lists. */
    public static final String KEY_ITEM = "item";

    /** Canonical status names, shared by VM and Wasm. */
    public static final String STATUS_OK = "OK";
    public static final String STATUS_DENIED = "DENIED";
    public static final String STATUS_UNSUPPORTED = "UNSUPPORTED";
    public static final String STATUS_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String STATUS_NOT_FOUND = "NOT_FOUND";
    public static final String STATUS_CONFLICT = "CONFLICT";
    public static final String STATUS_RATE_LIMITED = "RATE_LIMITED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_HOST_FAILURE = "HOST_FAILURE";

    private SyncBridgeCodec() {
    }

    /** Decoded {@code sync_invoke} request. */
    public record InvokeRequest(String backend, String action, Map<String, Object> args) {

        public InvokeRequest {
            if (backend == null || backend.isBlank()) {
                throw new IllegalArgumentException("backend must not be blank");
            }
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("action must not be blank");
            }
            args = Map.copyOf(Objects.requireNonNull(args, "args"));
        }
    }

    // ===== Requests =====

    /** Encodes the {@code sync_surface} request for one backend (empty = all). */
    public static byte[] encodeSurfaceRequest(String backend) {
        return TlvCodec.encodeString(backend == null ? "" : backend);
    }

    /** Decodes the backend of a {@code sync_surface} request; empty means all. */
    public static String decodeSurfaceRequest(byte[] data) {
        return requireType(TlvCodec.decodePrefix(data), TlvCodec.TYPE_STRING,
                "surface request").asString();
    }

    /** Encodes a {@code sync_invoke} request. */
    public static byte[] encodeInvokeRequest(InvokeRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, TlvCodec.TlvValue> payload = new LinkedHashMap<>();
        payload.put(KEY_BACKEND, TlvCodec.stringValue(request.backend()));
        payload.put(KEY_ACTION, TlvCodec.stringValue(request.action()));
        payload.put(KEY_ARGS, TlvCodec.mapValue(encodeArguments(request.args())));
        return TlvCodec.encodeMap(payload);
    }

    /** Decodes a {@code sync_invoke} request. */
    public static InvokeRequest decodeInvokeRequest(byte[] data) {
        Map<String, TlvCodec.TlvValue> payload = requireType(
                TlvCodec.decodePrefix(data), TlvCodec.TYPE_MAP, "invoke request").asMap();
        String backend = required(payload, KEY_BACKEND, TlvCodec.TYPE_STRING).asString();
        String action = required(payload, KEY_ACTION, TlvCodec.TYPE_STRING).asString();
        TlvCodec.TlvValue args = required(payload, KEY_ARGS, TlvCodec.TYPE_MAP);
        return new InvokeRequest(backend, action, decodeArguments(args));
    }

    // ===== Responses =====

    /** Encodes the published surfaces, sorted by backend and action id. */
    public static TlvCodec.TlvValue encodeSurfaceResponse(Collection<SyncSurface> surfaces) {
        Objects.requireNonNull(surfaces, "surfaces");
        List<TlvCodec.TlvValue> encoded = surfaces.stream()
                .sorted(Comparator.comparing(SyncSurface::backend))
                .map(SyncBridgeCodec::encodeSurface)
                .toList();
        return TlvCodec.listValue(encoded);
    }

    /**
     * Serializes the {@code sync_surface} response as one self-contained TLV
     * value, ready to be copied into a host buffer.
     */
    public static byte[] encodeSurfaceResponseBytes(Collection<SyncSurface> surfaces) {
        TlvCodec.TlvValue value = encodeSurfaceResponse(surfaces);
        return TlvCodec.encode(value.type(), value.payload());
    }

    /** Decodes a {@code sync_surface} response. */
    public static List<SyncSurface> decodeSurfaceResponse(byte[] data) {
        TlvCodec.TlvValue value = requireType(
                TlvCodec.decodePrefix(data), TlvCodec.TYPE_LIST, "surface response");
        List<SyncSurface> surfaces = new ArrayList<>();
        for (TlvCodec.TlvValue item : value.asList()) {
            surfaces.add(decodeSurface(item));
        }
        return List.copyOf(surfaces);
    }

    /** Encodes the outcome of one invocation. */
    public static TlvCodec.TlvValue encodeResult(SyncResult result) {
        Objects.requireNonNull(result, "result");
        Map<String, TlvCodec.TlvValue> payload = new LinkedHashMap<>();
        if (result instanceof SyncResult.Ok ok) {
            payload.put(KEY_STATUS, TlvCodec.stringValue(STATUS_OK));
            payload.put(KEY_RESULT, encodeValue(ok.value()));
        } else if (result instanceof SyncResult.RateLimited limited) {
            payload.put(KEY_STATUS, TlvCodec.stringValue(STATUS_RATE_LIMITED));
            payload.put(KEY_RESULT, TlvCodec.i64Value(limited.retryAfterMillis()));
        } else {
            payload.put(KEY_STATUS, TlvCodec.stringValue(statusName(result)));
            payload.put(KEY_REASON, TlvCodec.stringValue(reason(result)));
        }
        return TlvCodec.mapValue(payload);
    }

    /**
     * Serializes the {@code sync_invoke} response as one self-contained TLV
     * value, ready to be copied into a host buffer.
     */
    public static byte[] encodeResultBytes(SyncResult result) {
        TlvCodec.TlvValue value = encodeResult(result);
        return TlvCodec.encode(value.type(), value.payload());
    }

    /** Decodes the outcome of one invocation. */
    public static SyncResult decodeResult(byte[] data) {
        Map<String, TlvCodec.TlvValue> payload = requireType(
                TlvCodec.decodePrefix(data), TlvCodec.TYPE_MAP, "invoke response").asMap();
        String status = required(payload, KEY_STATUS, TlvCodec.TYPE_STRING).asString();
        return switch (status) {
            case STATUS_OK -> new SyncResult.Ok(decodeValue(
                    payload.getOrDefault(KEY_RESULT, TlvCodec.nullValue())));
            case STATUS_DENIED -> new SyncResult.Denied(reason(payload));
            case STATUS_UNSUPPORTED -> new SyncResult.Unsupported(reason(payload));
            case STATUS_INVALID_ARGUMENT -> new SyncResult.InvalidArgument(reason(payload));
            case STATUS_NOT_FOUND -> new SyncResult.NotFound(reason(payload));
            case STATUS_CONFLICT -> new SyncResult.Conflict(reason(payload));
            case STATUS_RATE_LIMITED -> new SyncResult.RateLimited(
                    required(payload, KEY_RESULT, TlvCodec.TYPE_I64).asI64());
            case STATUS_TIMEOUT -> new SyncResult.Timeout(reason(payload));
            case STATUS_HOST_FAILURE -> new SyncResult.HostFailure(reason(payload));
            default -> throw new IllegalArgumentException("estado de puente desconocido: " + status);
        };
    }

    // ===== Neutral value mapping =====

    /**
     * Maps a neutral Java value to TLV.
     *
     * @throws IllegalArgumentException if the value has no neutral representation
     */
    public static TlvCodec.TlvValue encodeValue(Object value) {
        if (value == null) {
            return TlvCodec.nullValue();
        }
        if (value instanceof String text) {
            return TlvCodec.stringValue(text);
        }
        if (value instanceof Boolean flag) {
            return TlvCodec.boolValue(flag);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return TlvCodec.i64Value(((Number) value).longValue());
        }
        if (value instanceof byte[] bytes) {
            return TlvCodec.bytesValue(bytes);
        }
        if (value instanceof Enum<?> constant) {
            return TlvCodec.stringValue(constant.name());
        }
        if (value instanceof List<?> list) {
            List<TlvCodec.TlvValue> items = new ArrayList<>(list.size());
            for (Object item : list) {
                items.add(encodeValue(item));
            }
            return TlvCodec.listValue(items);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, TlvCodec.TlvValue> entries = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(
                            "las claves de un mapa neutral deben ser strings");
                }
                entries.put(key, encodeValue(entry.getValue()));
            }
            return TlvCodec.mapValue(entries);
        }
        throw new IllegalArgumentException(
                "valor sin representación neutral: " + value.getClass().getName());
    }

    /** Maps a TLV value back to a neutral Java value. */
    public static Object decodeValue(TlvCodec.TlvValue value) {
        Objects.requireNonNull(value, "value");
        return switch (value.type()) {
            case TlvCodec.TYPE_NULL -> null;
            case TlvCodec.TYPE_STRING -> value.asString();
            case TlvCodec.TYPE_I64 -> value.asI64();
            case TlvCodec.TYPE_BOOL -> value.asBool();
            case TlvCodec.TYPE_BYTES -> value.asBytes();
            case TlvCodec.TYPE_LIST -> value.asList().stream()
                    .map(SyncBridgeCodec::decodeValue)
                    .toList();
            case TlvCodec.TYPE_MAP -> {
                Map<String, Object> map = new LinkedHashMap<>();
                value.asMap().forEach((key, item) -> map.put(key, decodeValue(item)));
                yield map;
            }
            default -> throw new IllegalArgumentException(
                    "tipo TLV sin valor neutral: " + value.type());
        };
    }

    // ===== Internals =====

    private static Map<String, TlvCodec.TlvValue> encodeArguments(Map<String, Object> args) {
        Map<String, TlvCodec.TlvValue> encoded = new LinkedHashMap<>();
        args.forEach((key, value) -> encoded.put(key, encodeValue(value)));
        return encoded;
    }

    private static Map<String, Object> decodeArguments(TlvCodec.TlvValue value) {
        Map<String, Object> args = new LinkedHashMap<>();
        value.asMap().forEach((key, item) -> args.put(key, decodeValue(item)));
        return args;
    }

    private static TlvCodec.TlvValue encodeSurface(SyncSurface surface) {
        List<TlvCodec.TlvValue> actions = surface.actions().stream()
                .sorted(Comparator.comparing(SyncActionSpec::id))
                .map(SyncBridgeCodec::encodeAction)
                .toList();
        Map<String, TlvCodec.TlvValue> payload = new LinkedHashMap<>();
        payload.put(KEY_BACKEND, TlvCodec.stringValue(surface.backend()));
        payload.put(KEY_CONTRACT_VERSION, TlvCodec.stringValue(surface.contractVersion().toString()));
        payload.put(KEY_ACTIONS, TlvCodec.listValue(actions));
        return TlvCodec.mapValue(payload);
    }

    private static SyncSurface decodeSurface(TlvCodec.TlvValue value) {
        Map<String, TlvCodec.TlvValue> payload = requireType(
                value, TlvCodec.TYPE_MAP, "surface").asMap();
        String backend = required(payload, KEY_BACKEND, TlvCodec.TYPE_STRING).asString();
        SemVer version = SemVer.parse(
                required(payload, KEY_CONTRACT_VERSION, TlvCodec.TYPE_STRING).asString());
        List<SyncActionSpec> actions = new ArrayList<>();
        for (TlvCodec.TlvValue item : requireType(
                required(payload, KEY_ACTIONS, TlvCodec.TYPE_LIST),
                TlvCodec.TYPE_LIST, "surface actions").asList()) {
            actions.add(decodeAction(item));
        }
        return new SyncSurface(backend, version, new java.util.LinkedHashSet<>(actions));
    }

    private static TlvCodec.TlvValue encodeAction(SyncActionSpec action) {
        List<TlvCodec.TlvValue> params = action.params().stream()
                .map(SyncBridgeCodec::encodeParam)
                .toList();
        Map<String, TlvCodec.TlvValue> payload = new LinkedHashMap<>();
        payload.put(KEY_ID, TlvCodec.stringValue(action.id()));
        payload.put(KEY_KIND, TlvCodec.stringValue(action.kind().name()));
        payload.put(KEY_PARAMS, TlvCodec.listValue(params));
        payload.put(KEY_RESULT, action.result() == null
                ? TlvCodec.nullValue() : encodeParam(action.result()));
        payload.put(KEY_IDEMPOTENT, TlvCodec.boolValue(action.idempotent()));
        payload.put(KEY_TIMEOUT_MILLIS, TlvCodec.i64Value(action.timeoutMillis()));
        return TlvCodec.mapValue(payload);
    }

    private static SyncActionSpec decodeAction(TlvCodec.TlvValue value) {
        Map<String, TlvCodec.TlvValue> payload = requireType(
                value, TlvCodec.TYPE_MAP, "action").asMap();
        List<SyncParamSpec> params = new ArrayList<>();
        for (TlvCodec.TlvValue item : requireType(
                required(payload, KEY_PARAMS, TlvCodec.TYPE_LIST),
                TlvCodec.TYPE_LIST, "action params").asList()) {
            params.add(decodeParam(item));
        }
        TlvCodec.TlvValue result = payload.getOrDefault(KEY_RESULT, TlvCodec.nullValue());
        return new SyncActionSpec(
                required(payload, KEY_ID, TlvCodec.TYPE_STRING).asString(),
                ActionKind.valueOf(
                        required(payload, KEY_KIND, TlvCodec.TYPE_STRING).asString()),
                params,
                result.isNull() ? null : decodeParam(result),
                required(payload, KEY_IDEMPOTENT, TlvCodec.TYPE_BOOL).asBool(),
                required(payload, KEY_TIMEOUT_MILLIS, TlvCodec.TYPE_I64).asI64());
    }

    private static TlvCodec.TlvValue encodeParam(SyncParamSpec param) {
        Map<String, TlvCodec.TlvValue> payload = new LinkedHashMap<>();
        payload.put(KEY_NAME, TlvCodec.stringValue(param.name()));
        payload.put(KEY_TYPE, TlvCodec.stringValue(param.type().name()));
        payload.put(KEY_REQUIRED, TlvCodec.boolValue(param.required()));
        payload.put(KEY_ITEM, param.item() == null
                ? TlvCodec.nullValue() : encodeParam(param.item()));
        return TlvCodec.mapValue(payload);
    }

    private static SyncParamSpec decodeParam(TlvCodec.TlvValue value) {
        Map<String, TlvCodec.TlvValue> payload = requireType(
                value, TlvCodec.TYPE_MAP, "param").asMap();
        TlvCodec.TlvValue item = payload.getOrDefault(KEY_ITEM, TlvCodec.nullValue());
        return new SyncParamSpec(
                required(payload, KEY_NAME, TlvCodec.TYPE_STRING).asString(),
                SyncValueType.valueOf(
                        required(payload, KEY_TYPE, TlvCodec.TYPE_STRING).asString()),
                required(payload, KEY_REQUIRED, TlvCodec.TYPE_BOOL).asBool(),
                item.isNull() ? null : decodeParam(item));
    }

    private static TlvCodec.TlvValue required(Map<String, TlvCodec.TlvValue> payload,
                                              String key, int type) {
        TlvCodec.TlvValue value = payload.get(key);
        if (value == null) {
            throw new IllegalArgumentException("falta la clave '" + key + "'");
        }
        return requireType(value, type, "'" + key + "'");
    }

    private static TlvCodec.TlvValue requireType(TlvCodec.TlvValue value, int type,
                                                 String what) {
        if (value.type() != type) {
            throw new IllegalArgumentException(
                    what + " requiere TLV tipo " + type + " y llegó " + value.type());
        }
        return value;
    }

    private static String statusName(SyncResult result) {
        if (result instanceof SyncResult.Denied) {
            return STATUS_DENIED;
        }
        if (result instanceof SyncResult.Unsupported) {
            return STATUS_UNSUPPORTED;
        }
        if (result instanceof SyncResult.InvalidArgument) {
            return STATUS_INVALID_ARGUMENT;
        }
        if (result instanceof SyncResult.NotFound) {
            return STATUS_NOT_FOUND;
        }
        if (result instanceof SyncResult.Conflict) {
            return STATUS_CONFLICT;
        }
        if (result instanceof SyncResult.RateLimited) {
            return STATUS_RATE_LIMITED;
        }
        if (result instanceof SyncResult.Timeout) {
            return STATUS_TIMEOUT;
        }
        if (result instanceof SyncResult.HostFailure) {
            return STATUS_HOST_FAILURE;
        }
        throw new IllegalArgumentException("resultado sin estado de puente: " + result);
    }

    private static String reason(SyncResult result) {
        if (result instanceof SyncResult.Denied denied) {
            return denied.reason();
        }
        if (result instanceof SyncResult.Unsupported unsupported) {
            return unsupported.reason();
        }
        if (result instanceof SyncResult.InvalidArgument invalid) {
            return invalid.reason();
        }
        if (result instanceof SyncResult.NotFound notFound) {
            return notFound.reason();
        }
        if (result instanceof SyncResult.Conflict conflict) {
            return conflict.reason();
        }
        if (result instanceof SyncResult.Timeout timeout) {
            return timeout.reason();
        }
        if (result instanceof SyncResult.HostFailure failure) {
            return failure.reason();
        }
        return "";
    }

    private static String reason(Map<String, TlvCodec.TlvValue> payload) {
        TlvCodec.TlvValue value = payload.get(KEY_REASON);
        return value == null ? "" : requireType(value, TlvCodec.TYPE_STRING, "reason").asString();
    }
}

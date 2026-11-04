package me.majhrs16.suite.iflow.wasm;

import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ABI de host de la política iFlow v3 para {@code iflow-wasm}.
 *
 * <p>El guest importa el módulo {@value #IMPORT_MODULE}. Cada función tiene una
 * firma fija y se valida antes de instanciar el artefacto. El export
 * {@value #EXPORT_DECIDE} se invoca una vez por mensaje y devuelve un estado:
 * la decisión de routing se registra mediante {@value #DECISION}.</p>
 *
 * <p>Los buffers usan {@link TlvCodec}: tipo, longitud y payload. La ausencia
 * explícita ({@link TlvCodec#TYPE_NULL}) es distinta de una cadena vacía. Los
 * ids de propiedades son propios de v3 y no reutilizan los {@code PROP_*} de la
 * VM v2.</p>
 */
public final class PolicyAbiV3 {

    /** Módulo de imports del host. */
    public static final String IMPORT_MODULE = "iflow";

    /** Export obligatorio que ejecuta la política. */
    public static final String EXPORT_DECIDE = "decide";

    // ===== Imports =====

    /** {@code read_property(scope, property, outPtr, outCap) -> status}. */
    public static final String READ_PROPERTY = "read_property";
    /** {@code write_property(scope, property, inPtr, inLen) -> status}. */
    public static final String WRITE_PROPERTY = "write_property";
    /** {@code transform_apply(kind, inPtr, inLen, outPtr, outCap) -> status}. */
    public static final String TRANSFORM_APPLY = "transform_apply";
    /** {@code set_channel(namePtr, nameLen) -> status}. */
    public static final String SET_CHANNEL = "set_channel";
    /** {@code permission_check(actorPtr, actorLen, nodePtr, nodeLen) -> 1|0}. */
    public static final String PERMISSION_CHECK = "permission_check";
    /** {@code channel_policy(namePtr, nameLen, kind, outPtr, outCap) -> status}. */
    public static final String CHANNEL_POLICY = "channel_policy";
    /** {@code decision(action, targetPtr, targetLen, reasonPtr, reasonLen) -> status}. */
    public static final String DECISION = "decision";
    /** {@code now_nanos() -> i64}. */
    public static final String NOW_NANOS = "now_nanos";
    /** {@code log(level, messagePtr, messageLen) -> status}. */
    public static final String LOG = "log";

    // ===== Status =====

    public static final int STATUS_OK = 0;
    public static final int STATUS_NOT_FOUND = 1;
    public static final int STATUS_BUFFER_TOO_SMALL = 2;
    public static final int STATUS_INVALID_ARGUMENT = 3;
    public static final int STATUS_DENIED = 4;
    public static final int STATUS_CONFLICT = 5;
    public static final int STATUS_HOST_FAILURE = 6;

    // ===== Acciones de decisión =====

    /** Entregar el mensaje al recipient actual. */
    public static final int ACTION_DELIVER = 0;
    /** Cancelar el evento de plataforma; el mensaje no se entrega. */
    public static final int ACTION_CANCEL_EVENT = 1;
    /** Error controlado de la política; el mensaje no se entrega. */
    public static final int ACTION_ERROR = 2;

    // ===== Scopes de propiedad =====

    public static final int SCOPE_MESSAGE = 1;
    public static final int SCOPE_EMITTER = 2;
    public static final int SCOPE_RECIPIENT = 3;
    public static final int SCOPE_CHANNEL = 4;
    public static final int SCOPE_SERVER = 5;

    // ===== Propiedades v3 =====
    //
    // MESSAGE. Los ids son independientes de la VM v2: no se copian
    // PROP_SOUNDS, PROP_PROCESSED ni PROP_ACTOR_WORLD.

    public static final int MESSAGE_CONTENT = 0x01;
    public static final int MESSAGE_CHANNEL = 0x02;
    public static final int MESSAGE_DIRECTION = 0x03;
    public static final int MESSAGE_TYPE = 0x04;
    public static final int MESSAGE_LANG_SOURCE = 0x05;
    public static final int MESSAGE_LANG_TARGET = 0x06;
    public static final int MESSAGE_COLOR_MODE = 0x07;
    public static final int MESSAGE_FORMAT_PAPI = 0x08;
    public static final int MESSAGE_TRANSLATE = 0x09;

    // ACTOR (SCOPE_EMITTER / SCOPE_RECIPIENT)

    public static final int ACTOR_NAME = 0x01;
    public static final int ACTOR_UUID = 0x02;
    public static final int ACTOR_LANGUAGE = 0x03;

    // CHANNEL

    public static final int CHANNEL_NAME = 0x01;
    public static final int CHANNEL_RATE = 0x02;
    public static final int CHANNEL_SEND_POLICY = 0x03;
    public static final int CHANNEL_RECEIVE_POLICY = 0x04;

    // ===== Kinds de transformación =====

    public static final int TRANSFORM_RESOLVE_PLACEHOLDERS = 0;
    public static final int TRANSFORM_DETECT_LANGUAGE = 1;

    // ===== Kinds de channel_policy =====

    public static final int CHANNEL_POLICY_SEND = 0;
    public static final int CHANNEL_POLICY_RECEIVE = 1;
    public static final int CHANNEL_POLICY_RATE = 2;

    // ===== Niveles de log =====

    public static final int LOG_DEBUG = 0;
    public static final int LOG_INFO = 1;
    public static final int LOG_WARN = 2;
    public static final int LOG_ERROR = 3;

    private static final Map<String, FunctionType> IMPORTS = buildImports();
    private static final Map<String, FunctionType> EXPORTS = Map.of(
        EXPORT_DECIDE, FunctionType.of(new ValType[0], new ValType[]{ValType.I32})
    );

    private PolicyAbiV3() {
    }

    /**
     * Firmas permitidas, indexadas por {@code "iflow.<import>"}. Un artefacto
     * puede usar un subconjunto; cualquier import usado debe coincidir.
     */
    public static Map<String, FunctionType> imports() {
        return IMPORTS;
    }

    /**
     * Firmas obligatorias de exports, indexadas por nombre.
     */
    public static Map<String, FunctionType> exports() {
        return EXPORTS;
    }

    private static Map<String, FunctionType> buildImports() {
        Map<String, FunctionType> map = new LinkedHashMap<>();
        map.put(key(READ_PROPERTY), FunctionType.of(
            new ValType[]{ValType.I32, ValType.I32, ValType.I32, ValType.I32},
            new ValType[]{ValType.I32}));
        map.put(key(WRITE_PROPERTY), FunctionType.of(
            new ValType[]{ValType.I32, ValType.I32, ValType.I32, ValType.I32},
            new ValType[]{ValType.I32}));
        map.put(key(TRANSFORM_APPLY), FunctionType.of(
            new ValType[]{ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32},
            new ValType[]{ValType.I32}));
        map.put(key(SET_CHANNEL), FunctionType.of(
            new ValType[]{ValType.I32, ValType.I32},
            new ValType[]{ValType.I32}));
        map.put(key(PERMISSION_CHECK), FunctionType.of(
            new ValType[]{ValType.I32, ValType.I32, ValType.I32, ValType.I32},
            new ValType[]{ValType.I32}));
        map.put(key(CHANNEL_POLICY), FunctionType.of(
            new ValType[]{ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32},
            new ValType[]{ValType.I32}));
        map.put(key(DECISION), FunctionType.of(
            new ValType[]{ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32},
            new ValType[]{ValType.I32}));
        map.put(key(NOW_NANOS), FunctionType.of(
            new ValType[0],
            new ValType[]{ValType.I64}));
        map.put(key(LOG), FunctionType.of(
            new ValType[]{ValType.I32, ValType.I32, ValType.I32},
            new ValType[]{ValType.I32}));
        return Collections.unmodifiableMap(map);
    }

    private static String key(String name) {
        return IMPORT_MODULE + "." + name;
    }
}

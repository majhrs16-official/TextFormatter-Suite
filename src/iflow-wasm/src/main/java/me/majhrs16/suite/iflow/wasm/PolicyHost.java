package me.majhrs16.suite.iflow.wasm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.WasmFunctionHandle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Host functions {@code iflow.*} enlazadas a una {@link PolicySession}.
 *
 * <p>Cada función traduce buffers de la memoria lineal del guest a valores
 * {@link TlvCodec} y devuelve un código de estado de {@link PolicyAbiV3}. Los
 * errores controlados se convierten en estado; las excepciones inesperadas se
 * convierten en {@link PolicyAbiV3#STATUS_HOST_FAILURE} sin propagarse al
 * guest.</p>
 *
 * <p>Los buffers de entrada de texto son UTF-8 estricto. Los buffers de salida
 * se escriben completos o devuelven {@code BUFFER_TOO_SMALL}; en ese caso, si
 * hay espacio para 4 bytes, se escribe la longitud requerida en little-endian
 * para que el guest pueda reintentar.</p>
 */
public final class PolicyHost {

    private final PolicySession session;
    private final List<HostFunction> functions;
    private final ImportValues imports;

    public PolicyHost(PolicySession session) {
        this.session = Objects.requireNonNull(session, "session");
        List<HostFunction> built = new ArrayList<>();
        built.add(function(PolicyAbiV3.READ_PROPERTY, this::readProperty));
        built.add(function(PolicyAbiV3.WRITE_PROPERTY, this::writeProperty));
        built.add(function(PolicyAbiV3.TRANSFORM_APPLY, this::transformApply));
        built.add(function(PolicyAbiV3.SET_CHANNEL, this::setChannel));
        built.add(function(PolicyAbiV3.PERMISSION_CHECK, this::permissionCheck));
        built.add(function(PolicyAbiV3.CHANNEL_POLICY, this::channelPolicy));
        built.add(function(PolicyAbiV3.DECISION, this::decision));
        built.add(function(PolicyAbiV3.NOW_NANOS, this::nowNanos));
        built.add(function(PolicyAbiV3.LOG, this::log));
        this.functions = List.copyOf(built);
        this.imports = ImportValues.builder()
            .addFunction(this.functions.toArray(new HostFunction[0]))
            .build();
    }

    public ImportValues importValues() {
        return imports;
    }

    public List<HostFunction> functions() {
        return functions;
    }

    public PolicySession session() {
        return session;
    }

    private HostFunction function(String name, WasmFunctionHandle handle) {
        String key = PolicyAbiV3.IMPORT_MODULE + "." + name;
        return new HostFunction(
            PolicyAbiV3.IMPORT_MODULE,
            name,
            PolicyAbiV3.imports().get(key),
            handle);
    }

    private long[] readProperty(Instance instance, long... args) {
        if (args.length < 4) {
            return status(PolicyAbiV3.STATUS_INVALID_ARGUMENT);
        }
        try {
            TlvCodec.TlvValue value = session.readProperty(i32(args[0]), i32(args[1]));
            return status(writeTlv(instance, value, i32(args[2]), i32(args[3])));
        } catch (PolicyException e) {
            return status(e.status());
        } catch (RuntimeException e) {
            return status(PolicyAbiV3.STATUS_HOST_FAILURE);
        }
    }

    private long[] writeProperty(Instance instance, long... args) {
        if (args.length < 4) {
            return status(PolicyAbiV3.STATUS_INVALID_ARGUMENT);
        }
        try {
            TlvCodec.TlvValue value = readTlv(instance, i32(args[2]), i32(args[3]));
            session.writeProperty(i32(args[0]), i32(args[1]), value);
            return status(PolicyAbiV3.STATUS_OK);
        } catch (PolicyException e) {
            return status(e.status());
        } catch (RuntimeException e) {
            return status(PolicyAbiV3.STATUS_HOST_FAILURE);
        }
    }

    private long[] transformApply(Instance instance, long... args) {
        if (args.length < 5) {
            return status(PolicyAbiV3.STATUS_INVALID_ARGUMENT);
        }
        try {
            String input = readString(instance, i32(args[1]), i32(args[2]));
            String output = session.transform(i32(args[0]), input);
            return status(writeTlv(instance, TlvCodec.stringValue(output),
                i32(args[3]), i32(args[4])));
        } catch (PolicyException e) {
            return status(e.status());
        } catch (RuntimeException e) {
            return status(PolicyAbiV3.STATUS_HOST_FAILURE);
        }
    }

    private long[] setChannel(Instance instance, long... args) {
        if (args.length < 2) {
            return status(PolicyAbiV3.STATUS_INVALID_ARGUMENT);
        }
        try {
            session.setChannel(readString(instance, i32(args[0]), i32(args[1])));
            return status(PolicyAbiV3.STATUS_OK);
        } catch (PolicyException e) {
            return status(e.status());
        } catch (RuntimeException e) {
            return status(PolicyAbiV3.STATUS_HOST_FAILURE);
        }
    }

    private long[] permissionCheck(Instance instance, long... args) {
        if (args.length < 4) {
            return new long[]{0};
        }
        try {
            String actor = readString(instance, i32(args[0]), i32(args[1]));
            String node = readString(instance, i32(args[2]), i32(args[3]));
            return new long[]{session.permissionCheck(actor, node) ? 1 : 0};
        } catch (RuntimeException e) {
            return new long[]{0};
        }
    }

    private long[] channelPolicy(Instance instance, long... args) {
        if (args.length < 5) {
            return status(PolicyAbiV3.STATUS_INVALID_ARGUMENT);
        }
        try {
            String name = readString(instance, i32(args[0]), i32(args[1]));
            TlvCodec.TlvValue value = session.channelPolicy(name, i32(args[2]));
            return status(writeTlv(instance, value, i32(args[3]), i32(args[4])));
        } catch (PolicyException e) {
            return status(e.status());
        } catch (RuntimeException e) {
            return status(PolicyAbiV3.STATUS_HOST_FAILURE);
        }
    }

    private long[] decision(Instance instance, long... args) {
        if (args.length < 5) {
            return status(PolicyAbiV3.STATUS_INVALID_ARGUMENT);
        }
        try {
            String target = readString(instance, i32(args[1]), i32(args[2]));
            String reason = readString(instance, i32(args[3]), i32(args[4]));
            return status(session.decide(i32(args[0]), target, reason));
        } catch (PolicyException e) {
            return status(e.status());
        } catch (RuntimeException e) {
            return status(PolicyAbiV3.STATUS_HOST_FAILURE);
        }
    }

    private long[] nowNanos(Instance instance, long... args) {
        return new long[]{session.nowNanos()};
    }

    private long[] log(Instance instance, long... args) {
        if (args.length < 3) {
            return status(PolicyAbiV3.STATUS_INVALID_ARGUMENT);
        }
        try {
            String text = readString(instance, i32(args[1]), i32(args[2]));
            return status(session.log(i32(args[0]), text));
        } catch (PolicyException e) {
            return status(e.status());
        } catch (RuntimeException e) {
            return status(PolicyAbiV3.STATUS_HOST_FAILURE);
        }
    }

    private static int writeTlv(Instance instance, TlvCodec.TlvValue value, int ptr, int cap) {
        if (ptr < 0 || cap < 0) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "buffer de salida inválido");
        }
        byte[] encoded = TlvCodec.encode(value.type(), value.payload());
        if (encoded.length > cap) {
            if (cap >= Integer.BYTES) {
                try {
                    instance.memory().write(ptr, littleEndian(encoded.length));
                } catch (RuntimeException ignored) {
                    // El guest puede reintentar; no hay espacio o el puntero no es válido.
                }
            }
            return PolicyAbiV3.STATUS_BUFFER_TOO_SMALL;
        }
        try {
            instance.memory().write(ptr, encoded);
        } catch (RuntimeException e) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "no se pudo escribir el buffer", e);
        }
        return PolicyAbiV3.STATUS_OK;
    }

    private static TlvCodec.TlvValue readTlv(Instance instance, int ptr, int len) {
        try {
            return TlvCodec.decode(readBytes(instance, ptr, len));
        } catch (IllegalArgumentException e) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "TLV inválido", e);
        }
    }

    private static String readString(Instance instance, int ptr, int len) {
        try {
            return TlvCodec.decodeUtf8(readBytes(instance, ptr, len));
        } catch (IllegalArgumentException e) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "UTF-8 inválido", e);
        }
    }

    private static byte[] readBytes(Instance instance, int ptr, int len) {
        if (ptr < 0 || len < 0) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "buffer de entrada inválido");
        }
        if (len == 0) {
            return new byte[0];
        }
        try {
            return instance.memory().readBytes(ptr, len);
        } catch (RuntimeException e) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "no se pudo leer el buffer", e);
        }
    }

    private static byte[] littleEndian(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array();
    }

    private static int i32(long value) {
        return (int) value;
    }

    private static long[] status(int status) {
        return new long[]{status};
    }
}

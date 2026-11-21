package me.majhrs16.suite.iflow.vm.host.v3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import me.majhrs16.suite.api.iflow.TlvCodec;
import me.majhrs16.suite.api.sync.SyncBridgeCodec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.HostCall;
import me.majhrs16.suite.iflow.vm.isa.v3.HostHandler;
import me.majhrs16.suite.iflow.vm.isa.v3.HostResult;
import me.majhrs16.suite.iflow.vm.isa.v3.HostStatus;
import me.majhrs16.suite.iflow.vm.isa.v3.MemoryFault;

/**
 * Implementation of the v3 {@code HOST} ABI described by ADR-037 §7.
 *
 * <p>The ABI keeps one {@link HostContext} per message handle. The context
 * owns the provisional property overlay and the single {@code ROUTE_DECISION}
 * latch, so two units on different cores cannot see each other's writes or
 * decisions. {@link #beginExecution(int)} opens a unit and
 * {@link #commitExecution(int)} or {@link #abortExecution(int)} closes it.
 * Registers outside {@code R0}/{@code R1} are not touched here; the CPU
 * installs only the status and result length.</p>
 */
public final class HostAbi implements HostHandler {

    public static final String KEY_SCOPE = "scope";
    public static final String KEY_PROPERTY = "property";
    public static final String KEY_VALUE = "value";
    public static final String KEY_PRESENT = "present";
    public static final String KEY_ACTOR = "actor";
    public static final String KEY_CHANNEL = "channel";
    public static final String KEY_PERMISSION = "permission";
    public static final String KEY_ALLOWED = "allowed";
    public static final String KEY_TARGET = "target";
    public static final String KEY_REASON = "reason";
    public static final String KEY_ERROR = "error";
    public static final String KEY_KIND = "kind";
    public static final String KEY_INPUT = "input";
    public static final String KEY_OUTPUT = "output";
    public static final String KEY_ID = "id";
    public static final String KEY_BACKEND = "backend";
    public static final String KEY_ACTION = "action";
    public static final String KEY_ARGS = "args";
    public static final String KEY_IDEMPOTENCY_KEY = "idempotencyKey";

    public static final String CAP_PROPERTY_READ = "iflow.property.read";
    public static final String CAP_PROPERTY_WRITE = "iflow.property.write";
    public static final String CAP_PERMISSION_CHECK = "iflow.permission.check";
    public static final String CAP_ROUTE_DECISION = "iflow.route.decision";
    public static final String CAP_TRANSFORM_APPLY = "iflow.transform.apply";
    public static final String CAP_MESSAGE_ID = "iflow.message.id";
    public static final String CAP_SYNC_SURFACE = "sync.surface";
    public static final String CAP_SYNC_INVOKE = "sync.invoke";

    public static final int TARGET_DELIVER = 0;
    public static final int TARGET_CANCEL_EVENT = 1;
    public static final int TARGET_ERROR = 2;

    private final HostBridge bridge;
    private final Map<Integer, HostContext> contexts = new ConcurrentHashMap<>();
    private final Object commitLock = new Object();

    public HostAbi(HostBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    /**
     * Opens an independent journal and decision latch for one message unit.
     * Reusing a handle while its unit is open fails instead of discarding a
     * provisional write set or decision.
     */
    public void beginExecution(int mident) {
        if (mident == CsrFile.NO_IDENTITY) {
            throw new IllegalArgumentException("MIDENT is required");
        }
        HostContext context = new HostContext(bridge);
        HostContext previous = contexts.putIfAbsent(mident, context);
        if (previous != null) {
            throw new IllegalStateException(
                    "message unit already open: " + mident);
        }
    }

    /**
     * Applies or discards the unit according to its route decision. Only
     * {@code DELIVER} commits the property overlay; {@code CANCEL_EVENT} and
     * {@code ERROR} record the decision without applying writes; no decision
     * discards the journal.
     */
    public void commitExecution(int mident) throws HostServiceException {
        HostContext context = openContext(mident);
        try {
            synchronized (context) {
                synchronized (commitLock) {
                    context.commit(bridge);
                }
            }
        } finally {
            contexts.remove(mident, context);
        }
    }

    /**
     * Discards the unit's provisional writes and decision.
     */
    public void abortExecution(int mident) {
        HostContext context = openContext(mident);
        try {
            synchronized (context) {
                context.abort();
            }
        } finally {
            contexts.remove(mident, context);
        }
    }

    public boolean isExecutionOpen(int mident) {
        return contexts.containsKey(mident);
    }

    private HostContext openContext(int mident) {
        HostContext context = contexts.get(mident);
        if (context == null) {
            throw new IllegalStateException(
                    "no open message unit: " + mident);
        }
        return context;
    }

    @Override
    public HostResult call(HostCall call) {
        Objects.requireNonNull(call, "call");
        if (call.flags() != 0
                || call.argumentLength() < 0 || call.argumentLength() > HostCall.MAX_BUFFER
                || call.resultCapacity() < 0 || call.resultCapacity() > HostCall.MAX_BUFFER) {
            return error(HostStatus.INVALID_ARGUMENT);
        }
        try {
            if (!isSupported(call.serviceId())) {
                return error(HostStatus.UNSUPPORTED);
            }
            boolean messageService = requiresMessage(call.serviceId());
            int identity = identityStatus(call, messageService);
            if (identity != HostStatus.OK) {
                return error(identity);
            }
            if (!messageService) {
                return dispatch(call, decodeArguments(call), bridge);
            }
            HostContext context = contexts.get(call.mident());
            if (context == null) {
                return error(HostStatus.NOT_READY);
            }
            synchronized (context) {
                return dispatch(call, decodeArguments(call), context);
            }
        } catch (MemoryFault fault) {
            return error(HostStatus.FAULT);
        } catch (HostServiceException fault) {
            return error(fault.status());
        } catch (IllegalArgumentException fault) {
            return error(HostStatus.INVALID_ARGUMENT);
        } catch (RuntimeException fault) {
            return error(HostStatus.INTERNAL);
        }
    }

    private HostResult dispatch(HostCall call, Map<String, TlvCodec.TlvValue> arguments,
                                HostBridge host) throws HostServiceException {
        return switch (call.serviceId()) {
            case HostServiceId.PROPERTY_READ -> propertyRead(call, arguments, host);
            case HostServiceId.PROPERTY_WRITE -> propertyWrite(call, arguments, host);
            case HostServiceId.PROPERTY_EXISTS -> propertyExists(call, arguments, host);
            case HostServiceId.PERMISSION_CHECK -> permissionCheck(call, arguments, host);
            case HostServiceId.ROUTE_DECISION -> routeDecision(call, arguments, host);
            case HostServiceId.TRANSFORM_APPLY -> transformApply(call, arguments, host);
            case HostServiceId.MESSAGE_ID -> messageId(call, host);
            case HostServiceId.SYNC_SURFACE -> syncSurface(call, arguments, host);
            case HostServiceId.SYNC_INVOKE -> syncInvoke(call, arguments, host);
            default -> throw new IllegalStateException(
                    "Unhandled HOST service: " + call.serviceId());
        };
    }

    private HostResult propertyRead(HostCall call, Map<String, TlvCodec.TlvValue> arguments,
                                    HostBridge host) throws HostServiceException {
        requireCapability(call, CAP_PROPERTY_READ);
        String scope = requiredString(arguments, KEY_SCOPE);
        String property = requiredString(arguments, KEY_PROPERTY);
        Optional<TlvCodec.TlvValue> value = host.propertyRead(
                call.pident(), call.mident(), scope, property, arguments);
        Map<String, TlvCodec.TlvValue> result = new LinkedHashMap<>();
        result.put(KEY_PRESENT, TlvCodec.boolValue(value.isPresent()));
        result.put(KEY_VALUE, value.orElseGet(TlvCodec::nullValue));
        return deliver(HostStatus.OK, TlvCodec.mapValue(result), call);
    }

    private HostResult propertyWrite(HostCall call, Map<String, TlvCodec.TlvValue> arguments,
                                     HostBridge host) throws HostServiceException {
        requireCapability(call, CAP_PROPERTY_WRITE);
        String scope = requiredString(arguments, KEY_SCOPE);
        String property = requiredString(arguments, KEY_PROPERTY);
        TlvCodec.TlvValue value = requiredValue(arguments, KEY_VALUE);
        host.propertyWrite(call.pident(), call.mident(), scope, property, value);
        return deliver(HostStatus.OK, null, call);
    }

    private HostResult propertyExists(HostCall call, Map<String, TlvCodec.TlvValue> arguments,
                                      HostBridge host) throws HostServiceException {
        requireCapability(call, CAP_PROPERTY_READ);
        String scope = requiredString(arguments, KEY_SCOPE);
        String property = requiredString(arguments, KEY_PROPERTY);
        boolean present = host.propertyExists(
                call.pident(), call.mident(), scope, property);
        Map<String, TlvCodec.TlvValue> result = new LinkedHashMap<>();
        result.put(KEY_PRESENT, TlvCodec.boolValue(present));
        return deliver(HostStatus.OK, TlvCodec.mapValue(result), call);
    }

    private HostResult permissionCheck(HostCall call, Map<String, TlvCodec.TlvValue> arguments,
                                       HostBridge host) throws HostServiceException {
        requireCapability(call, CAP_PERMISSION_CHECK);
        String actor = permissionField(arguments, KEY_ACTOR);
        String channel = permissionField(arguments, KEY_CHANNEL);
        String permission = permissionField(arguments, KEY_PERMISSION);
        boolean allowed = !actor.isBlank() && !channel.isBlank() && !permission.isBlank()
                && host.permissionCheck(
                        call.pident(), call.mident(), actor, channel, permission);
        Map<String, TlvCodec.TlvValue> result = new LinkedHashMap<>();
        result.put(KEY_ALLOWED, TlvCodec.boolValue(allowed));
        return deliver(HostStatus.OK, TlvCodec.mapValue(result), call);
    }

    private HostResult routeDecision(HostCall call, Map<String, TlvCodec.TlvValue> arguments,
                                     HostBridge host) throws HostServiceException {
        requireCapability(call, CAP_ROUTE_DECISION);
        int target = target(arguments);
        String reason = requiredString(arguments, KEY_REASON, true);
        String error = optionalString(arguments, KEY_ERROR);
        host.routeDecision(call.pident(), call.mident(), target, reason, error);
        return deliver(HostStatus.OK, null, call);
    }

    private HostResult transformApply(HostCall call, Map<String, TlvCodec.TlvValue> arguments,
                                      HostBridge host) throws HostServiceException {
        requireCapability(call, CAP_TRANSFORM_APPLY);
        String kind = requiredString(arguments, KEY_KIND);
        TlvCodec.TlvValue input = requiredValue(arguments, KEY_INPUT);
        TlvCodec.TlvValue output = host.transformApply(
                call.pident(), call.mident(), kind, input);
        Map<String, TlvCodec.TlvValue> result = new LinkedHashMap<>();
        result.put(KEY_OUTPUT, output);
        return deliver(HostStatus.OK, TlvCodec.mapValue(result), call);
    }

    private HostResult messageId(HostCall call, HostBridge host) throws HostServiceException {
        requireCapability(call, CAP_MESSAGE_ID);
        String id = host.messageId(call.mident());
        if (id == null || id.isEmpty()) {
            throw HostServiceException.notFound("message id is unavailable");
        }
        Map<String, TlvCodec.TlvValue> result = new LinkedHashMap<>();
        result.put(KEY_ID, TlvCodec.stringValue(id));
        return deliver(HostStatus.OK, TlvCodec.mapValue(result), call);
    }

    private HostResult syncSurface(HostCall call, Map<String, TlvCodec.TlvValue> arguments,
                                   HostBridge host) throws HostServiceException {
        requireCapability(call, CAP_SYNC_SURFACE);
        if (!arguments.isEmpty()) {
            throw HostServiceException.invalidArgument("SYNC_SURFACE takes no arguments");
        }
        Optional<List<SyncSurface>> surfaces = host.syncSurfaces(call.pident());
        if (surfaces.isEmpty()) {
            throw HostServiceException.unsupported("sync bridge is not installed");
        }
        return deliver(HostStatus.OK,
                SyncBridgeCodec.encodeSurfaceResponse(surfaces.get()), call);
    }

    private HostResult syncInvoke(HostCall call, Map<String, TlvCodec.TlvValue> arguments,
                                  HostBridge host) throws HostServiceException {
        requireCapability(call, CAP_SYNC_INVOKE);
        String backend = requiredString(arguments, KEY_BACKEND);
        String action = requiredString(arguments, KEY_ACTION);
        TlvCodec.TlvValue args = requiredValue(arguments, KEY_ARGS);
        if (args.type() != TlvCodec.TYPE_MAP) {
            throw HostServiceException.invalidArgument("'args' must be a TLV map");
        }
        String idempotencyKey = optionalString(arguments, KEY_IDEMPOTENCY_KEY);
        String reason = optionalString(arguments, KEY_REASON);

        requireActionCapability(call, backend, action);

        SyncResult result = host.syncInvoke(call.pident(), call.mident(),
                backend, action, args, idempotencyKey, reason);
        return deliver(syncStatus(result),
                SyncBridgeCodec.encodeResult(result), call);
    }

    private int identityStatus(HostCall call, boolean requiresMessage) {
        if (call.pident() == CsrFile.NO_IDENTITY) {
            return HostStatus.NOT_READY;
        }
        if (!bridge.verifyProgram(call.pident())) {
            return HostStatus.DENIED;
        }
        if (!requiresMessage) {
            return HostStatus.OK;
        }
        if (call.mident() == CsrFile.NO_IDENTITY) {
            return HostStatus.NOT_READY;
        }
        return bridge.verifyMessage(call.mident()) ? HostStatus.OK : HostStatus.DENIED;
    }

    private void requireCapability(HostCall call, String capability)
            throws HostServiceException {
        Set<String> granted = bridge.capabilities(call.pident());
        if (granted == null || !granted.contains(capability)) {
            throw HostServiceException.denied("missing capability: " + capability);
        }
    }

    /**
     * Sinks use {@code sync.<backend>.<action>}; module/host actions use
     * {@code module.<moduleId>.<action>} (ADR-037 §7.9). The grant table is
     * authoritative, so the exact capability the operator granted is the one
     * checked here.
     */
    private void requireActionCapability(HostCall call, String backend, String action)
            throws HostServiceException {
        Set<String> granted = bridge.capabilities(call.pident());
        String sink = "sync." + backend + "." + action;
        String module = "module." + backend + "." + action;
        if (granted == null || (!granted.contains(sink) && !granted.contains(module))) {
            throw HostServiceException.denied(
                    "missing action capability: " + sink + " or " + module);
        }
    }

    private HostResult deliver(int status, TlvCodec.TlvValue value, HostCall call) {
        byte[] bytes = value == null
                ? new byte[0]
                : TlvCodec.encode(value.type(), value.payload());
        if (bytes.length > call.resultCapacity()) {
            return new HostResult(HostStatus.LIMIT, bytes.length);
        }
        try {
            for (int index = 0; index < bytes.length; index++) {
                long address = Integer.toUnsignedLong(call.resultAddress()) + index;
                if (address > 0xFFFF_FFFFL) {
                    return error(HostStatus.FAULT);
                }
                call.memory().write((int) address, me.majhrs16.suite.iflow.vm.isa.v3.MemorySize.BYTE,
                        bytes[index]);
            }
        } catch (MemoryFault fault) {
            return error(HostStatus.FAULT);
        }
        return new HostResult(status, bytes.length);
    }

    private static HostResult error(int status) {
        return new HostResult(status, 0);
    }

    private Map<String, TlvCodec.TlvValue> decodeArguments(HostCall call) {
        if (call.argumentLength() == 0) {
            return Map.of();
        }
        byte[] bytes = call.argumentBytes();
        TlvCodec.TlvValue value = TlvCodec.decode(bytes);
        if (value.type() != TlvCodec.TYPE_MAP) {
            throw new IllegalArgumentException("HOST arguments must be a TLV map");
        }
        return value.asMap();
    }

    private static boolean isSupported(int serviceId) {
        return switch (serviceId) {
            case HostServiceId.PROPERTY_READ,
                    HostServiceId.PROPERTY_WRITE,
                    HostServiceId.PROPERTY_EXISTS,
                    HostServiceId.PERMISSION_CHECK,
                    HostServiceId.ROUTE_DECISION,
                    HostServiceId.TRANSFORM_APPLY,
                    HostServiceId.MESSAGE_ID,
                    HostServiceId.SYNC_SURFACE,
                    HostServiceId.SYNC_INVOKE -> true;
            default -> false;
        };
    }

    private static boolean requiresMessage(int serviceId) {
        return serviceId != HostServiceId.SYNC_SURFACE;
    }

    private static String requiredString(Map<String, TlvCodec.TlvValue> arguments, String key) {
        return requiredString(arguments, key, false);
    }

    private static String requiredString(Map<String, TlvCodec.TlvValue> arguments, String key,
                                         boolean allowBlank) {
        TlvCodec.TlvValue value = arguments.get(key);
        if (value == null || value.type() != TlvCodec.TYPE_STRING) {
            throw new IllegalArgumentException("missing or non-string '" + key + "'");
        }
        String text = value.asString();
        if (!allowBlank && text.isBlank()) {
            throw new IllegalArgumentException("blank '" + key + "'");
        }
        return text;
    }

    /**
     * Missing or explicit-null permission fields are absent and therefore
     * fail closed; only a wrong TLV type is a malformed argument.
     */
    private static String permissionField(Map<String, TlvCodec.TlvValue> arguments, String key) {
        TlvCodec.TlvValue value = arguments.get(key);
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.type() != TlvCodec.TYPE_STRING) {
            throw new IllegalArgumentException("non-string '" + key + "'");
        }
        return value.asString();
    }

    private static String optionalString(Map<String, TlvCodec.TlvValue> arguments, String key) {
        TlvCodec.TlvValue value = arguments.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.type() != TlvCodec.TYPE_STRING) {
            throw new IllegalArgumentException("non-string '" + key + "'");
        }
        return value.asString();
    }

    private static TlvCodec.TlvValue requiredValue(Map<String, TlvCodec.TlvValue> arguments,
                                                   String key) {
        TlvCodec.TlvValue value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing '" + key + "'");
        }
        return value;
    }

    private static int target(Map<String, TlvCodec.TlvValue> arguments) {
        TlvCodec.TlvValue value = requiredValue(arguments, KEY_TARGET);
        if (value.type() != TlvCodec.TYPE_I64) {
            throw new IllegalArgumentException("route target must be an i64");
        }
        long target = value.asI64();
        if (target < TARGET_DELIVER || target > TARGET_ERROR) {
            throw new IllegalArgumentException("unknown route target: " + target);
        }
        return (int) target;
    }

    private static int syncStatus(SyncResult result) {
        if (result instanceof SyncResult.Ok) {
            return HostStatus.OK;
        }
        if (result instanceof SyncResult.Denied) {
            return HostStatus.DENIED;
        }
        if (result instanceof SyncResult.Unsupported) {
            return HostStatus.UNSUPPORTED;
        }
        if (result instanceof SyncResult.InvalidArgument) {
            return HostStatus.INVALID_ARGUMENT;
        }
        if (result instanceof SyncResult.NotFound) {
            return HostStatus.NOT_FOUND;
        }
        if (result instanceof SyncResult.Conflict) {
            return HostStatus.CONFLICT;
        }
        if (result instanceof SyncResult.RateLimited) {
            return HostStatus.RATE_LIMITED;
        }
        if (result instanceof SyncResult.Timeout) {
            return HostStatus.TIMEOUT;
        }
        if (result instanceof SyncResult.HostFailure) {
            return HostStatus.INTERNAL;
        }
        throw new IllegalArgumentException("sync result has no HOST status: " + result);
    }
}

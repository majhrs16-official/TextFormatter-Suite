package me.majhrs16.suite.iflow.vm.host.v3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.TlvCodec;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.ColorMode;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.sync.HostActionContext;
import me.majhrs16.suite.api.sync.SyncBridgeCodec;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

/**
 * Host bridge of the {@code iflow-32} router.
 *
 * <p>One instance owns the open message units of one VM. A unit is a
 * {@code (message × recipient)} pair identified by its {@code MIDENT}: the
 * shared bridge resolves properties, permissions and message ids against that
 * unit, while {@link HostContext} keeps the provisional write set and the
 * single {@code ROUTE_DECISION} latch per handle. Two units never share state,
 * even when the VM runs them on different cores.</p>
 *
 * <p>The bridge is not the per-unit journal. {@link #propertyWrite} is
 * implemented by {@link HostContext}; if called directly it fails instead of
 * pretending to store a write. Committed writes arrive through
 * {@link #applyPropertyWrites(List)} and are applied as one batch.</p>
 */
public final class V3HostBridge implements HostBridge {

    /** Route decision latched by one unit, in ABI target codes. */
    public record Decision(int target, String reason, String error) {
    }

    private static final class Unit {
        private volatile Message message;
        private final Actor emitter;
        private final Actor recipient;
        private volatile Decision decision;

        private Unit(Message message, Actor emitter, Actor recipient) {
            this.message = Objects.requireNonNull(message, "message");
            this.emitter = Objects.requireNonNull(emitter, "emitter");
            this.recipient = Objects.requireNonNull(recipient, "recipient");
        }
    }

    private final ChannelRegistry channels;
    private final PermissionChecker permissions;
    private final Supplier<SyncBus> syncBus;
    private final String programSha256;
    private final Set<String> capabilities;
    private final Map<Integer, Unit> units = new ConcurrentHashMap<>();

    public V3HostBridge(ChannelRegistry channels,
                        PermissionChecker permissions,
                        Supplier<SyncBus> syncBus,
                        String programSha256,
                        Set<String> capabilities) {
        this.channels = Objects.requireNonNull(channels, "channels");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.syncBus = Objects.requireNonNull(syncBus, "syncBus");
        this.programSha256 = programSha256;
        this.capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    /**
     * Opens one unit. Reusing a handle that is still open fails: the host must
     * commit or abort the previous unit first.
     */
    public void open(int mident, Message message, Actor emitter, Actor recipient) {
        Unit unit = new Unit(message, emitter, recipient);
        if (units.putIfAbsent(mident, unit) != null) {
            throw new IllegalStateException("message unit already open: " + mident);
        }
    }

    /** Closes a unit and discards its provisional state. */
    public void close(int mident) {
        units.remove(mident);
    }

    /** @return decision latched by the committed unit, if any. */
    public Optional<Decision> decision(int mident) {
        Unit unit = units.get(mident);
        return unit == null ? Optional.empty() : Optional.ofNullable(unit.decision);
    }

    /** @return working message of the unit, including committed writes. */
    public Optional<Message> message(int mident) {
        Unit unit = units.get(mident);
        return unit == null ? Optional.empty() : Optional.of(unit.message);
    }

    @Override
    public boolean verifyProgram(int pident) {
        return pident == 0 && programSha256 != null;
    }

    @Override
    public boolean verifyMessage(int mident) {
        return units.containsKey(mident);
    }

    @Override
    public Set<String> capabilities(int pident) {
        return pident == 0 ? capabilities : Set.of();
    }

    @Override
    public Optional<TlvCodec.TlvValue> propertyRead(int pident, int mident,
                                                   String scope, String property,
                                                   Map<String, TlvCodec.TlvValue> arguments)
            throws HostServiceException {
        return read(requireUnit(mident), scope, property).map(TlvCodec::stringValue);
    }

    @Override
    public void propertyWrite(int pident, int mident, String scope, String property,
                              TlvCodec.TlvValue value) throws HostServiceException {
        throw HostServiceException.unsupported(
                "PROPERTY_WRITE belongs to the per-unit journal of MIDENT " + mident);
    }

    @Override
    public void validatePropertyWrite(int pident, int mident, String scope, String property,
                                      TlvCodec.TlvValue value) throws HostServiceException {
        requireUnit(mident);
        HostProperties.Writability writability = HostProperties.writability(scope, property);
        if (writability == HostProperties.Writability.UNKNOWN) {
            throw HostServiceException.notFound(
                    "unknown property: " + scope + "." + property);
        }
        if (writability == HostProperties.Writability.READ_ONLY) {
            throw HostServiceException.denied(
                    "read-only property: " + scope + "." + property);
        }
        requireValueType(scope, property, value);
    }

    @Override
    public void applyPropertyWrites(List<PropertyWrite> writes) throws HostServiceException {
        Map<Integer, Message.Builder> pending = new LinkedHashMap<>();
        Map<Integer, Unit> targets = new LinkedHashMap<>();
        for (PropertyWrite write : writes) {
            Unit unit = units.get(write.mident());
            if (unit == null) {
                throw HostServiceException.notFound(
                        "no open message unit: " + write.mident());
            }
            Message.Builder builder = pending.get(write.mident());
            if (builder == null) {
                builder = unit.message.toBuilder();
                pending.put(write.mident(), builder);
                targets.put(write.mident(), unit);
            }
            applyToBuilder(builder, write);
        }
        // Every builder is complete before the first assignment: the batch is
        // all-or-nothing for the host, which cannot roll back a partial apply.
        for (Map.Entry<Integer, Message.Builder> entry : pending.entrySet()) {
            targets.get(entry.getKey()).message = entry.getValue().build();
        }
    }

    @Override
    public boolean propertyExists(int pident, int mident, String scope, String property)
            throws HostServiceException {
        return read(requireUnit(mident), scope, property).isPresent();
    }

    @Override
    public boolean permissionCheck(int pident, int mident, String actor,
                                   String channel, String permission) {
        if (actor == null || actor.isBlank() || permission == null || permission.isBlank()) {
            return false;
        }
        return permissions.has(Actor.unknown(actor), permission);
    }

    @Override
    public void routeDecision(int pident, int mident, int target, String reason, String error)
            throws HostServiceException {
        Unit unit = requireUnit(mident);
        if (target < HostAbi.TARGET_DELIVER || target > HostAbi.TARGET_ERROR) {
            throw HostServiceException.invalidArgument("unknown route target: " + target);
        }
        unit.decision = new Decision(target, reason, error);
    }

    @Override
    public TlvCodec.TlvValue transformApply(int pident, int mident, String kind,
                                            TlvCodec.TlvValue input)
            throws HostServiceException {
        throw HostServiceException.unsupported(
                "TRANSFORM_APPLY is not configured for iflow-32: kind=" + kind);
    }

    @Override
    public String messageId(int mident) throws HostServiceException {
        return requireUnit(mident).message.id().toString();
    }

    @Override
    public Optional<List<SyncSurface>> syncSurfaces(int pident) {
        SyncBus bus = syncBus.get();
        return bus == null ? Optional.empty() : Optional.of(List.copyOf(bus.surfaces()));
    }

    @Override
    public SyncResult syncInvoke(int pident, int mident, String backend, String action,
                                 TlvCodec.TlvValue args, String idempotencyKey,
                                 String reason) throws HostServiceException {
        SyncBus bus = syncBus.get();
        if (bus == null) {
            return new SyncResult.Unsupported("sync bus is not installed");
        }
        if (programSha256 == null) {
            return new SyncResult.Unsupported("the program has no verified SHA-256 identity");
        }
        Unit unit = requireUnit(mident);
        Map<String, Object> javaArgs = new LinkedHashMap<>();
        args.asMap().forEach((key, value) ->
                javaArgs.put(key, SyncBridgeCodec.decodeValue(value)));
        SyncCall call;
        try {
            call = new SyncCall(backend, action, javaArgs, new Principal.Program(programSha256),
                    idempotencyKey == null ? mident + ":" + backend + "." + action : idempotencyKey,
                    reason == null ? "iflow-32 program " + programSha256 : reason);
        } catch (IllegalArgumentException failure) {
            return new SyncResult.InvalidArgument(failure.getMessage());
        }
        try {
            if (bus.sinkNames().contains(backend)) {
                return bus.invoke(call);
            }
            return bus.invokeHost(call, new HostActionContext(
                    unit.message, unit.emitter, unit.recipient, channels, permissions));
        } catch (RuntimeException failure) {
            return new SyncResult.HostFailure("sync bridge failure: " + failure.getMessage());
        }
    }

    private Unit requireUnit(int mident) throws HostServiceException {
        Unit unit = units.get(mident);
        if (unit == null) {
            throw HostServiceException.notFound("no open message unit: " + mident);
        }
        return unit;
    }

    private Optional<String> read(Unit unit, String scope, String property) {
        if (HostProperties.writability(scope, property) == HostProperties.Writability.UNKNOWN) {
            return Optional.empty();
        }
        if (HostProperties.SCOPE_MESSAGE.equals(scope)) {
            Message message = unit.message;
            return Optional.ofNullable(switch (property) {
                case HostProperties.MESSAGE_CONTENT -> message.text();
                case HostProperties.MESSAGE_CHANNEL -> message.channel();
                case HostProperties.MESSAGE_DIRECTION -> message.direction().toString();
                case HostProperties.MESSAGE_TYPE -> message.type().toString();
                case HostProperties.MESSAGE_LANG_SOURCE -> code(message.langSource());
                case HostProperties.MESSAGE_LANG_TARGET -> code(message.langTarget());
                case HostProperties.MESSAGE_COLOR_MODE ->
                        message.colorMode() == null ? null : message.colorMode().name();
                case HostProperties.MESSAGE_FORMAT_PAPI -> String.valueOf(message.formatPapi());
                case HostProperties.MESSAGE_TRANSLATE ->
                        String.valueOf(message.shouldTranslate());
                default -> null;
            });
        }
        if (HostProperties.SCOPE_EMITTER.equals(scope)) {
            return Optional.ofNullable(actorProperty(unit.emitter, property));
        }
        if (HostProperties.SCOPE_RECIPIENT.equals(scope)) {
            return Optional.ofNullable(actorProperty(unit.recipient, property));
        }
        Channel channel = channelOf(unit.message);
        return Optional.ofNullable(switch (property) {
            case HostProperties.CHANNEL_NAME -> channel.name();
            case HostProperties.CHANNEL_RATE -> String.valueOf(channel.rateLimitPerSecond());
            case HostProperties.CHANNEL_SEND_POLICY -> channel.sendPolicy();
            case HostProperties.CHANNEL_RECEIVE_POLICY -> channel.receivePolicy();
            default -> null;
        });
    }

    private Channel channelOf(Message message) {
        String path = message.channel() == null || message.channel().isBlank()
                ? "chat" : message.channel();
        return channels.resolve(path);
    }

    private static String actorProperty(Actor actor, String property) {
        return switch (property) {
            case HostProperties.ACTOR_NAME -> actor.name();
            case HostProperties.ACTOR_UUID ->
                    actor.uuid() == null ? null : actor.uuid().toString();
            case HostProperties.ACTOR_LANGUAGE -> code(actor.language());
            default -> null;
        };
    }

    private static String code(Language language) {
        return language == null ? null : language.code();
    }

    private static void requireValueType(String scope, String property, TlvCodec.TlvValue value)
            throws HostServiceException {
        if (!HostProperties.SCOPE_MESSAGE.equals(scope)) {
            throw HostServiceException.denied("scope is read-only: " + scope);
        }
        switch (property) {
            case HostProperties.MESSAGE_CONTENT -> {
                if (value.type() != TlvCodec.TYPE_STRING) {
                    throw HostServiceException.invalidArgument("content must be a TLV string");
                }
            }
            case HostProperties.MESSAGE_LANG_SOURCE, HostProperties.MESSAGE_LANG_TARGET -> {
                if (value.type() != TlvCodec.TYPE_STRING
                        || Language.of(value.asString()).isEmpty()) {
                    throw HostServiceException.invalidArgument(
                            property + " must be a known language code");
                }
            }
            case HostProperties.MESSAGE_COLOR_MODE -> {
                if (value.type() != TlvCodec.TYPE_STRING
                        || ColorMode.of(value.asString()).isEmpty()) {
                    throw HostServiceException.invalidArgument(
                            "colorMode must be a known color mode");
                }
            }
            case HostProperties.MESSAGE_FORMAT_PAPI, HostProperties.MESSAGE_TRANSLATE -> {
                if (value.type() != TlvCodec.TYPE_BOOL) {
                    throw HostServiceException.invalidArgument(property + " must be a TLV bool");
                }
            }
            default -> throw HostServiceException.notFound("unknown property: " + property);
        }
    }

    private static void applyToBuilder(Message.Builder builder, PropertyWrite write)
            throws HostServiceException {
        String property = write.property();
        TlvCodec.TlvValue value = write.value();
        try {
            switch (property) {
                case HostProperties.MESSAGE_CONTENT -> builder.text(value.asString());
                case HostProperties.MESSAGE_LANG_SOURCE ->
                        builder.langSource(Language.of(value.asString()).orElseThrow());
                case HostProperties.MESSAGE_LANG_TARGET ->
                        builder.langTarget(Language.of(value.asString()).orElseThrow());
                case HostProperties.MESSAGE_COLOR_MODE ->
                        builder.colorMode(ColorMode.of(value.asString()).orElseThrow());
                case HostProperties.MESSAGE_FORMAT_PAPI -> builder.formatPapi(value.asBool());
                case HostProperties.MESSAGE_TRANSLATE -> builder.translate(value.asBool());
                default -> throw HostServiceException.notFound(
                        "unknown property: " + write.scope() + "." + property);
            }
        } catch (IllegalStateException | IllegalArgumentException failure) {
            throw HostServiceException.invalidArgument(
                    "invalid value for " + property + ": " + failure.getMessage());
        }
    }
}

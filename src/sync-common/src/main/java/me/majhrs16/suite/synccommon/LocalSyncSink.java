package me.majhrs16.suite.synccommon;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.DeliveryEnvelope;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.LocalDelivery;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncActionSpec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncParamSpec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.api.sync.SyncValueType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Sync sink that delivers the rendered envelope through the host
 * {@link LocalDelivery} port and exposes the platform environment.
 *
 * <p>Platform packages ({@code sync-bukkit}, {@code sync-fabric}) publish one
 * instance with their own backend namespace and their {@link LocalEnvironment}.
 * The sink resolves the delivery port lazily on every delivery: the runtime
 * registers it after module activation, so a lookup at {@code start()} would be
 * too early.</p>
 *
 * <p>{@code message.send} is explicitly unsupported: the local path needs the
 * recipient-specific units the pipeline already rendered. External callers
 * must use {@code message.deliver}.</p>
 *
 * <p>Native surface: {@code local.info}, plus the environment actions
 * {@code player.list}, {@code player.info}, {@code player.message},
 * {@code player.kick}, {@code console.command}, {@code scoreboard.set},
 * {@code event.info} and {@code event.cancel}. Every native action requires a
 * {@link Principal.Program}; {@link Principal.Official} receives
 * {@link SyncResult.Denied} because it may only invoke the least common
 * multiple. Event actions operate on the event bound to the current message,
 * never on an arbitrary platform event.</p>
 */
public final class LocalSyncSink implements SyncSink {

    private static final String LOCAL_INFO = "local.info";
    private static final String PLAYER_LIST = "player.list";
    private static final String PLAYER_INFO = "player.info";
    private static final String PLAYER_MESSAGE = "player.message";
    private static final String PLAYER_KICK = "player.kick";
    private static final String CONSOLE_COMMAND = "console.command";
    private static final String SCOREBOARD_SET = "scoreboard.set";
    private static final String EVENT_INFO = "event.info";
    private static final String EVENT_CANCEL = "event.cancel";

    private static final Set<String> NATIVE_ACTIONS = Set.of(
            LOCAL_INFO, PLAYER_LIST, PLAYER_INFO, PLAYER_MESSAGE, PLAYER_KICK,
            CONSOLE_COMMAND, SCOREBOARD_SET, EVENT_INFO, EVENT_CANCEL);

    private final String backend;
    private final ServiceRegistry services;
    private final LocalEnvironment environment;
    private final SyncSurface surface;
    private volatile SyncListener listener;

    /**
     * @param backend     backend namespace, which is also the sink name
     * @param services    module-scoped registry that exposes the local port
     * @param environment platform environment executed by the native actions
     */
    public LocalSyncSink(String backend, ServiceRegistry services,
                         LocalEnvironment environment) {
        if (backend == null || backend.isBlank()) {
            throw new IllegalArgumentException("backend must not be blank");
        }
        this.backend = backend;
        this.services = Objects.requireNonNull(services, "services");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.surface = buildSurface(backend);
    }

    @Override
    public String name() {
        return backend;
    }

    @Override
    public SyncSurface surface() {
        return surface;
    }

    @Override
    public SyncResult invoke(SyncCall call) {
        Objects.requireNonNull(call, "call");
        if (!backend.equals(call.backend())) {
            return SyncSink.super.invoke(call);
        }
        if (SyncSurface.MESSAGE_SEND.equals(call.action())) {
            return new SyncResult.Unsupported(
                    "local backend '" + backend + "' requires message.deliver");
        }
        if (!NATIVE_ACTIONS.contains(call.action())) {
            return SyncSink.super.invoke(call);
        }
        if (call.principal() instanceof Principal.Official) {
            return new SyncResult.Denied(
                    "Principal.Official may only invoke the least common multiple");
        }
        if (LOCAL_INFO.equals(call.action())) {
            return localInfo();
        }
        try {
            return executeNative(call);
        } catch (LocalActionException failure) {
            return mapFailure(failure);
        } catch (RuntimeException failure) {
            return new SyncResult.HostFailure(describe(failure));
        }
    }

    private SyncResult localInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name());
        info.put("surfaceVersion", surface.contractVersion().toString());
        info.put("localDeliveryRegistered",
                services.find(LocalDelivery.class).isPresent());
        info.put("deliverySemantics", deliverySemantics().name());
        info.put("ordering", ordering().name());
        info.put("environmentRegistered", true);
        info.put("eventBound", environment.currentEvent().isPresent());
        return new SyncResult.Ok(info);
    }

    private SyncResult executeNative(SyncCall call) {
        return switch (call.action()) {
            case PLAYER_LIST -> {
                List<Map<String, Object>> players = environment.players().stream()
                        .map(LocalPlayer::toValue)
                        .toList();
                yield new SyncResult.Ok(Map.of("players", players));
            }
            case PLAYER_INFO -> {
                String reference = requiredString(call, "player");
                if (reference == null) {
                    yield invalidPlayerReference();
                }
                Optional<LocalPlayer> player = environment.player(reference);
                yield player.<SyncResult>map(value ->
                                new SyncResult.Ok(value.toValue()))
                        .orElseGet(() -> new SyncResult.NotFound(
                                "player '" + reference + "' is not online"));
            }
            case PLAYER_MESSAGE -> {
                String reference = requiredString(call, "player");
                if (reference == null) {
                    yield invalidPlayerReference();
                }
                String text = requiredString(call, "text");
                if (text == null) {
                    yield new SyncResult.InvalidArgument(
                            "'text' must be a non-blank string");
                }
                environment.sendMessage(reference, text);
                yield new SyncResult.Ok(Map.of());
            }
            case PLAYER_KICK -> {
                String reference = requiredString(call, "player");
                if (reference == null) {
                    yield invalidPlayerReference();
                }
                String reason = optionalString(call, "reason");
                if (reason == null) {
                    reason = "kicked by suite";
                }
                environment.kick(reference, reason);
                yield new SyncResult.Ok(Map.of());
            }
            case CONSOLE_COMMAND -> {
                String command = requiredString(call, "command");
                if (command == null) {
                    yield new SyncResult.InvalidArgument(
                            "'command' must be a non-blank string");
                }
                environment.runConsoleCommand(command);
                yield new SyncResult.Ok(Map.of());
            }
            case SCOREBOARD_SET -> {
                String objective = requiredString(call, "objective");
                if (objective == null) {
                    yield new SyncResult.InvalidArgument(
                            "'objective' must be a non-blank string");
                }
                String entry = requiredString(call, "entry");
                if (entry == null) {
                    yield new SyncResult.InvalidArgument(
                            "'entry' must be a non-blank string");
                }
                Integer value = requiredInteger(call, "value");
                if (value == null) {
                    yield new SyncResult.InvalidArgument(
                            "'value' must be an integer");
                }
                environment.setScore(objective, entry, value);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("objective", objective);
                result.put("entry", entry);
                result.put("value", value);
                yield new SyncResult.Ok(result);
            }
            case EVENT_INFO -> {
                Optional<LocalEvent> event = environment.currentEvent();
                yield event.<SyncResult>map(value ->
                                new SyncResult.Ok(value.info()))
                        .orElseGet(() -> new SyncResult.NotFound(
                                "no platform event is bound to the current message"));
            }
            case EVENT_CANCEL -> {
                LocalEvent event = environment.currentEvent()
                        .orElseThrow(() -> new LocalActionException(
                                LocalActionException.Kind.NOT_FOUND,
                                "no platform event is bound to the current message"));
                event.cancel();
                yield new SyncResult.Ok(Map.of());
            }
            default -> new SyncResult.Unsupported(
                    "action '" + call.canonicalName() + "' is not implemented");
        };
    }

    private static SyncResult invalidPlayerReference() {
        return new SyncResult.InvalidArgument(
                "'player' must be a non-blank string");
    }

    private static SyncResult mapFailure(LocalActionException failure) {
        return switch (failure.kind()) {
            case NOT_FOUND -> new SyncResult.NotFound(failure.getMessage());
            case UNSUPPORTED -> new SyncResult.Unsupported(failure.getMessage());
            case INVALID_ARGUMENT -> new SyncResult.InvalidArgument(failure.getMessage());
            case HOST_FAILURE -> new SyncResult.HostFailure(failure.getMessage());
        };
    }

    private static String requiredString(SyncCall call, String name) {
        Object value = call.args().get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private static String optionalString(SyncCall call, String name) {
        Object value = call.args().get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private static Integer requiredInteger(SyncCall call, String name) {
        Object value = call.args().get(name);
        return value instanceof Integer number ? number : null;
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
    }

    @Override
    public SyncResult deliver(DeliveryEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        LocalDelivery delivery = services.find(LocalDelivery.class).orElse(null);
        if (delivery == null) {
            return new SyncResult.HostFailure(
                    "local backend '" + backend
                            + "' cannot deliver: LocalDelivery is not registered");
        }
        try {
            return new SyncResult.Ok(delivery.deliver(envelope));
        } catch (RuntimeException failure) {
            return new SyncResult.HostFailure(describe(failure));
        }
    }

    @Override
    public DeliverySemantics deliverySemantics() {
        return DeliverySemantics.EXACTLY_ONCE;
    }

    @Override
    public Ordering ordering() {
        return Ordering.GLOBAL;
    }

    @Override
    public void start() {
        // No connection to open: the local port is resolved per delivery.
    }

    @Override
    public void stop() {
        // No connection to close.
    }

    @Override
    public void send(Message message) {
        throw new UnsupportedOperationException(
                "local backend '" + backend + "' requires message.deliver");
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    private static SyncSurface buildSurface(String backend) {
        SyncSurface lcm = SyncSurface.lcm(
                backend, SyncSurface.CURRENT_CONTRACT_VERSION);
        Set<SyncActionSpec> actions = new LinkedHashSet<>(lcm.actions());
        actions.add(new SyncActionSpec(
                LOCAL_INFO,
                ActionKind.READ,
                List.of(),
                SyncParamSpec.optional("info", SyncValueType.OBJECT),
                true,
                5_000));
        actions.add(new SyncActionSpec(
                PLAYER_LIST,
                ActionKind.READ,
                List.of(),
                new SyncParamSpec("players", SyncValueType.OBJECT, true,
                        SyncParamSpec.required("player", SyncValueType.OBJECT), false),
                true,
                5_000));
        actions.add(new SyncActionSpec(
                PLAYER_INFO,
                ActionKind.READ,
                List.of(SyncParamSpec.required("player", SyncValueType.STRING)),
                SyncParamSpec.optional("player", SyncValueType.OBJECT),
                true,
                5_000));
        actions.add(new SyncActionSpec(
                PLAYER_MESSAGE,
                ActionKind.WRITE,
                List.of(
                        SyncParamSpec.required("player", SyncValueType.STRING),
                        SyncParamSpec.required("text", SyncValueType.STRING)),
                null,
                false,
                10_000));
        actions.add(new SyncActionSpec(
                PLAYER_KICK,
                ActionKind.DESTRUCTIVE,
                List.of(
                        SyncParamSpec.required("player", SyncValueType.STRING),
                        SyncParamSpec.optional("reason", SyncValueType.STRING)),
                null,
                false,
                10_000));
        actions.add(new SyncActionSpec(
                CONSOLE_COMMAND,
                ActionKind.DESTRUCTIVE,
                List.of(SyncParamSpec.required("command", SyncValueType.STRING)),
                null,
                false,
                15_000));
        actions.add(new SyncActionSpec(
                SCOREBOARD_SET,
                ActionKind.WRITE,
                List.of(
                        SyncParamSpec.required("objective", SyncValueType.STRING),
                        SyncParamSpec.required("entry", SyncValueType.STRING),
                        SyncParamSpec.required("value", SyncValueType.INTEGER)),
                SyncParamSpec.optional("score", SyncValueType.OBJECT),
                true,
                10_000));
        actions.add(new SyncActionSpec(
                EVENT_INFO,
                ActionKind.READ,
                List.of(),
                SyncParamSpec.optional("event", SyncValueType.OBJECT),
                true,
                5_000));
        actions.add(new SyncActionSpec(
                EVENT_CANCEL,
                ActionKind.WRITE,
                List.of(),
                null,
                true,
                10_000));
        return new SyncSurface(backend, SyncSurface.CURRENT_CONTRACT_VERSION, actions);
    }
}

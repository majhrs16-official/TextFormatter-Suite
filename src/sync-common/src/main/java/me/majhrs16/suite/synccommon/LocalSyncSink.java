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
import java.util.Set;

/**
 * Sync sink that delivers the rendered envelope through the host
 * {@link LocalDelivery} port.
 *
 * <p>Platform packages ({@code sync-bukkit}, {@code sync-fabric}) publish one
 * instance with their own backend namespace. The sink resolves the port lazily
 * on every delivery: the runtime registers it after module activation, so a
 * lookup at {@code start()} would be too early.</p>
 *
 * <p>{@code message.send} is explicitly unsupported: the local path needs the
 * recipient-specific units the pipeline already rendered. External callers
 * must use {@code message.deliver}.</p>
 *
 * <p>Native surface: {@code local.info} reports the backend namespace, the
 * surface version, whether the host registered the {@link LocalDelivery} port
 * and the declared delivery semantics. It is read-only, takes no arguments and
 * is available before {@code start()}, because the local sink opens no
 * connection. {@link Principal.Official} may only invoke the least common
 * multiple, so it receives {@link SyncResult.Denied}.</p>
 */
public final class LocalSyncSink implements SyncSink {

    private static final String LOCAL_INFO = "local.info";

    private final String backend;
    private final ServiceRegistry services;
    private final SyncSurface surface;
    private volatile SyncListener listener;

    /**
     * @param backend  backend namespace, which is also the sink name
     * @param services module-scoped registry that exposes the local port
     */
    public LocalSyncSink(String backend, ServiceRegistry services) {
        if (backend == null || backend.isBlank()) {
            throw new IllegalArgumentException("backend must not be blank");
        }
        this.backend = backend;
        this.services = Objects.requireNonNull(services, "services");
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
        if (!LOCAL_INFO.equals(call.action())) {
            return SyncSink.super.invoke(call);
        }
        if (call.principal() instanceof Principal.Official) {
            return new SyncResult.Denied(
                    "Principal.Official may only invoke the least common multiple");
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name());
        info.put("surfaceVersion", surface.contractVersion().toString());
        info.put("localDeliveryRegistered",
                services.find(LocalDelivery.class).isPresent());
        info.put("deliverySemantics", deliverySemantics().name());
        info.put("ordering", ordering().name());
        return new SyncResult.Ok(info);
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
            String message = failure.getMessage();
            return new SyncResult.HostFailure(
                    message == null || message.isBlank()
                            ? failure.getClass().getSimpleName()
                            : failure.getClass().getSimpleName() + ": " + message);
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
        return new SyncSurface(backend, SyncSurface.CURRENT_CONTRACT_VERSION, actions);
    }
}

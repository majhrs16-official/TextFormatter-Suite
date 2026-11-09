package me.majhrs16.suite.synccommon;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.DeliveryEnvelope;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.LocalDelivery;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

import java.util.Objects;

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
 */
public final class LocalSyncSink implements SyncSink {

    private final String backend;
    private final ServiceRegistry services;
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
    }

    @Override
    public String name() {
        return backend;
    }

    @Override
    public SyncSurface surface() {
        return SyncSurface.lcm(backend, SyncSurface.CURRENT_CONTRACT_VERSION);
    }

    @Override
    public SyncResult invoke(SyncCall call) {
        Objects.requireNonNull(call, "call");
        if (SyncSurface.MESSAGE_SEND.equals(call.action())) {
            return new SyncResult.Unsupported(
                    "local backend '" + backend + "' requires message.deliver");
        }
        return SyncSink.super.invoke(call);
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
}

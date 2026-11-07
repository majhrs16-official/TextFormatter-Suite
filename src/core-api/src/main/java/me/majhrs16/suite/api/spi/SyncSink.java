package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A bidirectional edge connector that bridges Minecraft chat to an external
 * platform (Discord, Telegram, HTTP webhook, raw TCP/UDP).
 *
 * <p>Lifecycle is explicit ({@link #start()}/{@link #stop()}); outbound
 * deliveries go through the published surface (at least {@code message.send});
 * inbound events are pushed back to the engine by the platform task invoking
 * {@link SyncListener}. Modules advertising the {@code sync-sink} capability
 * must provide an implementation bound to this contract.</p>
 *
 * <p>The default {@link #surface()} publishes only the least common multiple
 * ({@code backend.info} and {@code message.send}) and the default
 * {@link #invoke(SyncCall)} executes it. A backend that publishes native
 * actions must override both methods; declaring an action without executing it
 * is a contract violation.</p>
 */
public interface SyncSink {

    /** Delivery reliability semantics for this sink. */
    enum DeliverySemantics {
        /** Best-effort, no ordering, no retransmission (e.g., UDP). */
        BEST_EFFORT,
        /** Ordered, at-least-once with retries (e.g., TCP, WebSocket, HTTP). */
        AT_LEAST_ONCE,
        /** Exactly-once with deduplication (e.g., Velocity with acks). */
        EXACTLY_ONCE
    }

    /** Ordering guarantee for this sink. */
    enum Ordering {
        /** No ordering guarantee. */
        NONE,
        /** Messages delivered in send order per channel. */
        PER_CHANNEL,
        /** Globally ordered across all channels. */
        GLOBAL
    }

    /** @return a stable connector id, e.g. {@code discord} or {@code webhook}. */
    String name();

    /**
     * Actions published by this backend.
     *
     * <p>The default is the official least common multiple: {@code backend.info}
     * and {@code message.send}. The backend namespace is this sink's
     * {@link #name()}, which the bus validates before registering it.</p>
     *
     * @return the published surface, never {@code null}
     */
    default SyncSurface surface() {
        return SyncSurface.lcm(name(), SyncSurface.CURRENT_CONTRACT_VERSION);
    }

    /**
     * Executes one published action.
     *
     * <p>The default implementation serves the LCM only: {@code backend.info}
     * returns name and surface version, and {@code message.send} delegates to
     * {@link #send(Message)}. Every failure is explicit: an unpublished action
     * returns {@link SyncResult.Unsupported}, a wrong argument type returns
     * {@link SyncResult.InvalidArgument}, and a delivery crash returns
     * {@link SyncResult.HostFailure}.</p>
     *
     * @param call invocation whose backend must match this sink
     * @return the explicit outcome, never {@code null}
     */
    default SyncResult invoke(SyncCall call) {
        Objects.requireNonNull(call, "call");
        SyncSurface published = surface();
        if (!published.backend().equals(call.backend())) {
            return new SyncResult.Unsupported("backend '" + call.backend()
                    + "' is not served by '" + published.backend() + "'");
        }
        if (published.action(call.action()).isEmpty()) {
            return new SyncResult.Unsupported(
                    "action '" + call.canonicalName() + "' is not published");
        }

        if (SyncSurface.BACKEND_INFO.equals(call.action())) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", name());
            info.put("version", published.contractVersion().toString());
            info.put("surfaceVersion", published.contractVersion().toString());
            return new SyncResult.Ok(info);
        }

        if (SyncSurface.MESSAGE_SEND.equals(call.action())) {
            Object value = call.args().get("message");
            if (!(value instanceof Message message)) {
                return new SyncResult.InvalidArgument("'message' must be a Message");
            }
            if (call.args().containsKey("destination")) {
                return new SyncResult.Unsupported(
                        "destination routing is not implemented by the default LCM");
            }
            try {
                send(message);
                return new SyncResult.Ok(null);
            } catch (Exception failure) {
                return new SyncResult.HostFailure(describe(failure));
            }
        }

        return new SyncResult.Unsupported(
                "action '" + call.canonicalName() + "' is not implemented");
    }

    /** @return a non-null description that keeps the failure class visible */
    private static String describe(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
    }

    /** @return delivery reliability semantics for this transport. */
    DeliverySemantics deliverySemantics();

    /** @return ordering guarantee for this transport. */
    Ordering ordering();

    /** Connects to the remote and arms the inbound listener. */
    void start() throws Exception;

    /** Disconnects and releases platform resources. */
    void stop();

    /** Sends a message outbound to the remote platform. */
    void send(Message message) throws Exception;

    /**
     * Registers the inbound callback. Exactly one listener is supported;
     * replacing it atomically swaps the handler.
     */
    void setListener(SyncListener listener);
}
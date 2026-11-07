package me.majhrs16.suite.api.spi;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

/**
 * Central hub for cross-platform message synchronization.
 * <p>
 * The SyncBus acts as a unified pipeline that:
 * <ul>
 *   <li>Aggregates all registered {@link SyncSink} implementations (Discord, Telegram, HTTP, TCP/UDP, WebSocket, Velocity)</li>
 *   <li>Fans out outbound messages to all connected sinks</li>
 *   <li>Routes inbound messages from sinks back through the engine via {@link SyncListener}</li>
 *   <li>Provides lifecycle management (start/stop) for all sinks</li>
 * </ul>
 * <p>
 * This contract lives in {@code core-api} so sink packages can register
 * themselves without depending on the {@code sync-bus} implementation package.
 * </p>
 */
public interface SyncBus extends AutoCloseable {

    /**
     * Registers a sink with the bus. The sink will receive outbound messages
     * and its inbound events will be routed through the bus's listener.
     *
     * <p>If the bus is already running, the sink is started immediately, which
     * is what makes hot-loading a sink package work.</p>
     *
     * @param sink the sink to register, must not be null
     * @throws IllegalStateException if a sink with the same name is already
     *                               registered or cannot be started
     */
    void register(SyncSink sink);

    /**
     * Unregisters a sink from the bus and stops it.
     *
     * @param name the sink name (as returned by {@link SyncSink#name()})
     * @return true if the sink was found and removed
     */
    boolean unregister(String name);

    /**
     * @return all currently registered sinks
     */
    Collection<SyncSink> sinks();

    /**
     * @return the names of all registered sinks
     */
    Set<String> sinkNames();

    /**
     * @return every surface published by the registered backends, in
     *         registration order
     */
    Set<SyncSurface> surfaces();

    /**
     * Executes one action against its backend.
     *
     * <p>The bus validates that the backend is registered, that its surface
     * publishes the action, that the arguments match the parameter specs and
     * that the caller is authorized. The action is bounded by its declared
     * {@code timeoutMillis}; a crash is returned as
     * {@link SyncResult.HostFailure} and never escapes.</p>
     *
     * <p>Authority is fail-closed: {@link me.majhrs16.suite.api.sync.Principal.Official}
     * may invoke only the least common multiple, and
     * {@link me.majhrs16.suite.api.sync.Principal.Program} is denied until the
     * operator grant store exists.</p>
     *
     * @param call invocation, must not be null
     * @return the explicit outcome, never {@code null}
     */
    SyncResult invoke(SyncCall call);

    /**
     * Fans out a least-common-multiple action to every registered backend.
     *
     * <p>Only {@code backend.info} and {@code message.send} are accepted. The
     * {@code backend} field of {@code call} does not select a destination: it
     * is replaced by each surface's backend. Any other action returns one
     * {@link SyncResult.Unsupported} per backend, because a generic broadcast
     * must never turn a native action into a destructive mass operation.</p>
     *
     * @param call action template, must not be null
     * @return one result per registered backend, in {@link #surfaces()} order
     */
    List<SyncResult> broadcast(SyncCall call);

    /**
     * Comfort overload of {@code message.send} for every registered backend.
     *
     * <p>This is the same delivery path as {@link #invoke(SyncCall)}; it is not
     * a second route. A duplicate suppressed by the deduplication window
     * returns an empty list, because no delivery was attempted.</p>
     *
     * @param message the message to send, must not be null
     * @return one result per targeted backend
     */
    List<SyncResult> send(Message message);

    /**
     * Directed form of {@code message.send}.
     *
     * @param backend backend namespace, not a sink display name
     * @param message the message to send, must not be null
     * @return one result for the targeted backend, or one
     *         {@link SyncResult.Unsupported} when it is not registered
     */
    List<SyncResult> sendTo(String backend, Message message);

    /**
     * Sets the inbound listener that receives messages from all sinks.
     * <p>
     * The listener is called when any sink receives an inbound message.
     * It should route the message through the engine's normal pipeline
     * (iFlow router, formatter, etc.).
     * </p>
     *
     * @param listener the inbound listener, or null to disable
     */
    void setInboundListener(SyncListener listener);

    /**
     * Starts all registered sinks. Idempotent.
     *
     * @throws Exception if any sink fails to start
     */
    void start() throws Exception;

    /**
     * Stops all registered sinks gracefully. Idempotent.
     */
    void stop();

    /**
     * Checks if the bus has any registered sinks.
     */
    boolean isEmpty();

    /**
     * Gets the current inbound listener.
     */
    SyncListener getInboundListener();

    // ========== Metrics ==========

    /** @return total messages broadcast */
    long getTotalBroadcast();

    /** @return total messages dropped (queue full, retries exhausted) */
    long getTotalDropped();

    /** @return total retry attempts */
    long getTotalRetries();

    /** @return total messages deduplicated */
    long getTotalDeduped();

    /** @return current global queue size */
    int getGlobalQueueSize();

    /** @return remaining global queue capacity */
    int getGlobalQueueRemaining();
}

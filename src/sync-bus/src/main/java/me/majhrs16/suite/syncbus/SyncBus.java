package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
 * This replaces the fragmented wiring where each platform adapter managed its sinks individually.
 * The bus ensures consistent delivery semantics, deduplication, and backpressure across all transports.
 * </p>
 */
public interface SyncBus extends AutoCloseable {

    /**
     * Registers a sink with the bus. The sink will receive outbound messages
     * and its inbound events will be routed through the bus's listener.
     *
     * @param sink the sink to register, must not be null
     * @throws IllegalStateException if a sink with the same name is already registered
     */
    void register(SyncSink sink);

    /**
     * Unregisters a sink from the bus.
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
     * Sends a message to all registered sinks (fan-out).
     * <p>
     * Delivery is best-effort per sink; failures are logged but don't stop
     * delivery to other sinks.
     * </p>
     *
     * @param message the message to broadcast, must not be null
     * @return the number of sinks that successfully enqueued the message for delivery
     */
    int broadcast(Message message);

    /**
     * Sends a message to all registered sinks and returns futures for tracking
     * actual delivery completion per sink.
     * <p>
     * Unlike {@link #broadcast(Message)}, this method returns a map of sink names
     * to {@link CompletableFuture} that complete when the sink has processed the
     * message (successfully or with failure). This allows callers to await actual
     * delivery rather than just queue acceptance.
     * </p>
     *
     * @param message the message to broadcast, must not be null
     * @return map of sink name to future completing when that sink finishes processing
     */
    Map<String, CompletableFuture<Void>> broadcastAsync(Message message);

    /**
     * Sends a message to a specific sink by name.
     *
     * @param sinkName the target sink name
     * @param message the message to send
     * @return true if the sink exists and accepted the message
     */
    boolean sendTo(String sinkName, Message message);

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
package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.pipeline.DeliveryEnvelope;
import me.majhrs16.suite.api.pipeline.DeliverySummary;

/**
 * Platform port that performs the physical local delivery of a rendered
 * envelope.
 *
 * <p>The host implements this port with its platform adapter (Bukkit, Fabric);
 * the local {@code SyncSink} resolves it and delegates the envelope. Keeping
 * the port neutral means the sink package selects and identifies the backend,
 * while the adapter owns threading, sounds, redirects and the concrete chat
 * API.</p>
 *
 * <p>The implementation must be synchronous from the caller's point of view:
 * when it returns, every unit has been delivered, queued for later delivery,
 * silenced, redirected, discarded or counted as failed. A rate-limited unit
 * accepted by the bounded queue is reported as {@code queued}, never as
 * delivered. Asynchronous delivery scheduled by the implementation (for
 * example a delayed message) is counted as delivered when it is successfully
 * scheduled.</p>
 */
public interface LocalDelivery {

    /**
     * Delivers every unit in {@code envelope}.
     *
     * @param envelope rendered units and their original message
     * @return explicit counters for the units in the envelope
     */
    DeliverySummary deliver(DeliveryEnvelope envelope);
}

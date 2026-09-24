package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Message;

/**
 * Callback that receives inbound traffic from an external platform and events
 * about the connector state. The engine wires this to the iFlow router so
 * remote messages enter the same filtering/formatting pipeline.
 */
public interface SyncListener {

    /** An inbound message arrived from the remote platform. */
    void onMessage(SyncSink sink, Message message);

    /** The remote connection dropped; {@code reason} may be null. */
    void onDisconnect(SyncSink sink, String reason);
}